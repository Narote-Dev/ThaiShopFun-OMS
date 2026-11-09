package com.thaishopfun.oms.reconciliation;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.invariant.InvariantChecker;
import com.thaishopfun.oms.invariant.Violation;
import com.thaishopfun.oms.listing.ListingApiException;
import com.thaishopfun.oms.order.ReconciliationIssueRepository;
import com.thaishopfun.oms.stockdrift.StockDriftService;
import com.thaishopfun.oms.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class ReconciliationService {

  public static final String RUN_METRIC = "oms.reconciliation.issues";
  public static final String ALERT_METRIC = "oms.reconciliation.alert";

  private final JdbcTemplate jdbc;
  private final ReconciliationIssueRepository issues;
  private final StockDriftService stockDrift;
  private final InvariantChecker invariants;
  private final JsonMapper json;
  private final MeterRegistry meters;
  private final TransactionTemplate tx;

  public ReconciliationService(
      JdbcTemplate jdbc,
      ReconciliationIssueRepository issues,
      StockDriftService stockDrift,
      InvariantChecker invariants,
      JsonMapper json,
      MeterRegistry meters,
      PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    this.issues = issues;
    this.stockDrift = stockDrift;
    this.invariants = invariants;
    this.json = json;
    this.meters = meters;
    tx = new TransactionTemplate(transactions);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public record Result(UUID run_id, int issues_found, Map<String, Integer> by_rule) {}

  public Result run(UUID tenant, String trigger) {
    return inTenant(tenant, () -> tx.execute(status -> doRun(trigger)));
  }

  private Result doRun(String trigger) {
    // Step 1: Open one run row and serialize writers per tenant.
    UUID tenantId = TenantContext.requireTenantId();
    UUID runId = UuidV7.generate();
    jdbc.update(
        """
        INSERT INTO reconciliation_run (id, tenant_id, trigger, started_at)
        VALUES (?, ?, ?, clock_timestamp())
        """,
        runId,
        tenantId,
        trigger);
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 29))", (rs, n) -> 0, tenantId.toString());

    Map<String, Integer> counts = new LinkedHashMap<>();
    int total = 0;

    // Step 2: Order-level rules from plan §1.5.
    for (String rule : ReconciliationRules.ORDER_RULES) {
      int found = applyOrderRule(runId, rule);
      counts.put(rule, found);
      total += found;
    }

    // Step 3: Stock drift (rule 7) runs in its own service; count open drift afterward.
    stockDrift.run(tenantId);
    int driftOpen =
        jdbc.queryForObject(
            """
            SELECT count(*)::int FROM reconciliation_issue
            WHERE rule = ? AND status <> 'RESOLVED'
            """,
            Integer.class,
            ReconciliationRules.STOCK_DRIFT);
    counts.put(ReconciliationRules.STOCK_DRIFT, driftOpen);
    total += driftOpen;

    // Step 4: Invariant break → shop-level issue + alert metric.
    List<Violation> violations = invariants.checkTenant(tenantId);
    if (violations.isEmpty()) {
      issues.resolveShopRule(runId, ReconciliationRules.INVARIANT_VIOLATION);
      counts.put(ReconciliationRules.INVARIANT_VIOLATION, 0);
    } else {
      ObjectNode details = json.createObjectNode();
      ArrayNode codes = details.putArray("codes");
      ArrayNode samples = details.putArray("violations");
      for (Violation violation : violations) {
        codes.add(violation.code());
        if (samples.size() < 20) {
          ObjectNode row = samples.addObject();
          row.put("code", violation.code());
          row.put(
              "entity_ids",
              violation.entityIds().stream()
                  .map(UUID::toString)
                  .reduce((a, b) -> a + "," + b)
                  .orElse(""));
        }
        meters.counter(ALERT_METRIC, List.of(Tag.of("code", violation.code()))).increment();
        meters
            .counter(
                com.thaishopfun.oms.invariant.InvariantJob.VIOLATIONS_METRIC,
                List.of(Tag.of("code", violation.code())))
            .increment();
      }
      issues.upsertOpenShop(
          runId, ReconciliationRules.INVARIANT_VIOLATION, json.writeValueAsString(details));
      counts.put(ReconciliationRules.INVARIANT_VIOLATION, 1);
      total += 1;
    }

    // Step 5: Finish run and audit.
    jdbc.update(
        """
        UPDATE reconciliation_run
        SET finished_at = clock_timestamp(), issues_found = ?
        WHERE id = ? AND tenant_id = ?
        """,
        total,
        runId,
        tenantId);
    ObjectNode after = json.createObjectNode();
    after.put("run_id", runId.toString());
    after.put("trigger", trigger);
    counts.forEach(after::put);
    jdbc.update(
        """
        INSERT INTO audit_log (id, tenant_id, actor_type, actor_id, action, entity_type, entity_id, "after")
        VALUES (?, ?, 'SYSTEM', NULL, 'RECONCILIATION_RUN', 'reconciliation_run', ?, ?::jsonb)
        """,
        UuidV7.generate(),
        tenantId,
        runId,
        json.writeValueAsString(after));
    for (var entry : counts.entrySet()) {
      if (entry.getValue() > 0) {
        meters
            .counter(RUN_METRIC, List.of(Tag.of("rule", entry.getKey())))
            .increment(entry.getValue());
      }
    }
    return new Result(runId, total, counts);
  }

  private int applyOrderRule(UUID runId, String rule) {
    List<UUID> violating = queryViolations(rule);
    for (UUID orderId : violating) {
      issues.upsertOpen(runId, rule, orderId, "{}");
    }
    issues.resolveRuleExcept(runId, rule, violating);
    return violating.size();
  }

  private List<UUID> queryViolations(String rule) {
    String sql =
        switch (rule) {
          case ReconciliationRules.SHIP_BY_LATE ->
              """
              SELECT id FROM sales_order
              WHERE order_status = 'ACTIVE'
                AND fulfillment_status IN ('READY_TO_PICK', 'PICKING', 'PACKED')
                AND ship_by IS NOT NULL AND ship_by < clock_timestamp()
              """;
          case ReconciliationRules.SHIPPED_UNPAID ->
              """
              SELECT id FROM sales_order
              WHERE fulfillment_status IN ('SHIPPED', 'DELIVERED')
                AND payment_status NOT IN ('PAID', 'COD_PENDING')
              """;
          case ReconciliationRules.PAID_AFTER_CANCEL ->
              """
              SELECT id FROM sales_order
              WHERE order_status = 'CANCELLED' AND payment_status = 'PAID'
              """;
          case ReconciliationRules.COD_COLLECTION_OVERDUE ->
              """
              SELECT o.id FROM sales_order o
              JOIN shipment s ON s.tenant_id = o.tenant_id AND s.order_id = o.id
              WHERE o.payment_status = 'COD_PENDING'
                AND o.fulfillment_status = 'DELIVERED'
                AND s.delivered_at IS NOT NULL
                AND s.delivered_at < clock_timestamp() - interval '14 days'
              """;
          case ReconciliationRules.REFUND_MISSING_AFTER_RETURN ->
              """
              SELECT DISTINCT rr.order_id AS id
              FROM return_request rr
              WHERE rr.status = 'RECEIVED'
                AND rr.received_at IS NOT NULL
                AND rr.received_at < clock_timestamp() - interval '7 days'
                AND NOT EXISTS (
                  SELECT 1 FROM refund r
                  WHERE r.tenant_id = rr.tenant_id
                    AND r.order_id = rr.order_id
                    AND r.status = 'SUCCEEDED'
                )
              """;
          case ReconciliationRules.PAYMENT_AMOUNT_MISMATCH ->
              """
              SELECT o.id FROM sales_order o
              WHERE EXISTS (
                SELECT 1 FROM payment_status_snapshot p
                WHERE p.tenant_id = o.tenant_id AND p.order_id = o.id
                  AND p.observed_at = (
                    SELECT max(p2.observed_at) FROM payment_status_snapshot p2
                    WHERE p2.tenant_id = o.tenant_id AND p2.order_id = o.id
                  )
                  AND p.amount <> o.grand_total
              )
              """;
          default -> throw new IllegalArgumentException("unknown rule: " + rule);
        };
    return jdbc.query(sql, (rs, n) -> rs.getObject("id", UUID.class));
  }

  public List<Map<String, Object>> listIssues(String status, String rule) {
    return tx.execute(
        s -> {
          String inRules =
              ReconciliationRules.ALL.stream()
                  .map(r -> "'" + r + "'")
                  .reduce((a, b) -> a + "," + b)
                  .orElse("");
          StringBuilder sql =
              new StringBuilder(
                  """
                  SELECT i.id, i.rule, i.status, i.order_id, i.details::text,
                         i.created_at, i.updated_at, o.external_order_id
                  FROM reconciliation_issue i
                  LEFT JOIN sales_order o
                    ON o.tenant_id = i.tenant_id AND o.id = i.order_id
                  WHERE i.rule IN ("""
                      + inRules
                      + ")");
          List<Object> args = new ArrayList<>();
          if (status != null) {
            sql.append(" AND i.status = ?");
            args.add(status);
          }
          if (rule != null) {
            sql.append(" AND i.rule = ?");
            args.add(rule);
          }
          sql.append(" ORDER BY i.created_at DESC LIMIT 200");
          return jdbc.query(
              sql.toString(),
              (rs, n) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getString("id"));
                row.put("rule", rs.getString("rule"));
                row.put("status", rs.getString("status"));
                row.put(
                    "order_id",
                    rs.getObject("order_id") == null
                        ? null
                        : rs.getObject("order_id", UUID.class).toString());
                row.put("external_order_id", rs.getString("external_order_id"));
                row.put("details", json.readTree(rs.getString("details")));
                row.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
                row.put("updated_at", rs.getObject("updated_at", OffsetDateTime.class));
                return row;
              },
              args.toArray());
        });
  }

  public void patchStatus(UUID issueId, String next) {
    if (!Set.of("ACK", "RESOLVED").contains(next)) {
      throw new ListingApiException(422, "VALIDATION_FAILED", "status is invalid");
    }
    int updated = issues.updateStatus(issueId, next);
    if (updated == 0) {
      throw new ListingApiException(404, "NOT_FOUND", "issue not found");
    }
    UUID tenantId = TenantContext.requireTenantId();
    ObjectNode after = json.createObjectNode();
    after.put("status", next);
    jdbc.update(
        """
        INSERT INTO audit_log (id, tenant_id, actor_type, actor_id, action, entity_type, entity_id, "after")
        VALUES (?, ?, 'USER', ?, 'RECONCILIATION_ISSUE_STATUS', 'reconciliation_issue', ?, ?::jsonb)
        """,
        UuidV7.generate(),
        tenantId,
        TenantContext.requireUserId().toString(),
        issueId,
        json.writeValueAsString(after));
  }

  private <T> T inTenant(UUID tenant, Supplier<T> work) {
    UUID previous = TenantContext.tenantId();
    UUID user = TenantContext.userId();
    TenantContext.set(tenant, Objects.equals(previous, tenant) ? user : null);
    try {
      return work.get();
    } finally {
      if (previous == null) TenantContext.clear();
      else TenantContext.set(previous, user);
    }
  }
}
