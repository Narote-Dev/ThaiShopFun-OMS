package com.thaishopfun.oms.stockdrift;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.channel.*;
import com.thaishopfun.oms.channel.api.ListingPage;
import com.thaishopfun.oms.stockpush.StockPushService;
import com.thaishopfun.oms.tenant.TenantContext;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class StockDriftService {
  private final JdbcTemplate jdbc;
  private final ChannelAdapterRegistry adapters;
  private final StockPushService pushes;
  private final JsonMapper json;
  private final TransactionTemplate tx;

  public StockDriftService(
      JdbcTemplate jdbc,
      ChannelAdapterRegistry adapters,
      StockPushService pushes,
      JsonMapper json,
      PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    this.adapters = adapters;
    this.pushes = pushes;
    this.json = json;
    tx = new TransactionTemplate(transactions);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  record Account(UUID id, String shop) {}

  record Issue(UUID id, String status, String details, OffsetDateTime updated) {}

  record Listing(
      UUID id,
      UUID account,
      String external,
      UUID sku,
      Integer expected,
      boolean controlled,
      boolean pending) {}

  public record Result(UUID run_id, int drifting, int repush_events, boolean superseded) {}

  /** Fetch outside transactions. A failed/incomplete scan cannot resolve existing issues. */
  public Result run(UUID tenant) {
    return inTenant(
        tenant,
        () -> {
          OffsetDateTime started =
              tx.execute(
                  s -> jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class));
          List<Account> accounts =
              tx.execute(
                  s ->
                      jdbc.query(
                          """
          SELECT id, external_shop_id FROM channel_account
          WHERE channel = 'TSF' AND status = 'CONNECTED' ORDER BY id
          """,
                          (rs, n) -> new Account(rs.getObject(1, UUID.class), rs.getString(2))));
          Map<UUID, Map<String, Integer>> seen = new LinkedHashMap<>();
          for (Account account : accounts) {
            Map<String, Integer> quantities = new HashMap<>();
            Set<String> cursors = new HashSet<>();
            String cursor = null;
            do {
              String pageCursor = cursor;
              // An adapter may use nested tenant scopes. Restore our tenant and actor before
              // the next page and, critically, before the transaction manager binds the RLS GUC.
              ListingPage page =
                  inTenant(
                      tenant,
                      () ->
                          adapters
                              .require(Channel.TSF)
                              .listListings(
                                  new ChannelAccountRef(tenant, account.id(), account.shop()),
                                  pageCursor));
              for (var listing : page.listings()) {
                if (listing.available() < 0
                    || quantities.putIfAbsent(listing.listingSkuId(), listing.available()) != null)
                  throw new IllegalStateException("Invalid listing page");
              }
              cursor = page.nextCursor();
              if (cursor != null && !cursor.isBlank() && !cursors.add(cursor))
                throw new IllegalStateException("Repeated listing cursor");
            } while (cursor != null && !cursor.isBlank());
            seen.put(account.id(), quantities);
          }
          return tx.execute(s -> reconcile(tenant, started, seen));
        });
  }

  private Result reconcile(
      UUID tenant, OffsetDateTime started, Map<UUID, Map<String, Integer>> seen) {
    UUID run = UuidV7.generate();
    // Covers the no-row case too; one aggregate writer per tenant across all JVMs.
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 23))", (rs, n) -> 0, tenant.toString());
    List<Issue> issues =
        jdbc.query(
            """
        SELECT id, status, details::text, updated_at FROM reconciliation_issue
        WHERE rule = 'STOCK_DRIFT' AND order_id IS NULL AND status <> 'RESOLVED' FOR UPDATE
        """,
            (rs, n) ->
                new Issue(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getObject(4, OffsetDateTime.class)));
    Issue issue = issues.isEmpty() ? null : issues.get(0);
    if (issue != null && issue.updated().isAfter(started)) return new Result(run, 0, 0, true);
    // Same lock order as T15: accounts, listings, pending rows.
    jdbc.query(
        "SELECT id FROM channel_account WHERE channel = 'TSF' ORDER BY id FOR UPDATE",
        (rs, n) -> rs.getObject(1, UUID.class));
    List<Listing> listings =
        jdbc.query(
            """
        SELECT cl.id, cl.channel_account_id, cl.external_sku_id, cl.sku_id, cl.last_exposed_qty,
          ca.status = 'CONNECTED' AND NOT ca.stock_sync_paused AND cl.sku_id IS NOT NULL
          AND cl.removed_at IS NULL AND (ca.mode = 'ACTIVE' OR (ca.mode = 'CONTROL' AND cl.stock_control)),
          EXISTS (SELECT 1 FROM stock_push_pending p WHERE p.listing_id = cl.id AND p.dirty_since IS NOT NULL)
          OR EXISTS (SELECT 1 FROM outbox_event o
            WHERE o.aggregate_id = ca.id::text AND o.event_type = 'stock.updated'
            AND (o.status IN ('PENDING', 'IN_FLIGHT') OR o.created_at >= ? OR o.sent_at >= ?)
            AND EXISTS (SELECT 1 FROM jsonb_array_elements(o.payload->'data'->'items') item
              WHERE item->>'listing_sku_id' = cl.external_sku_id))
        FROM channel_listing cl JOIN channel_account ca ON ca.id = cl.channel_account_id
        WHERE ca.channel = 'TSF' ORDER BY cl.id FOR NO KEY UPDATE OF cl
        """,
            (rs, n) ->
                new Listing(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getString(3),
                    rs.getObject(4, UUID.class),
                    rs.getObject(5, Integer.class),
                    rs.getBoolean(6),
                    rs.getBoolean(7)),
            started,
            started);
    Map<String, JsonNode> previous = new HashMap<>();
    if (issue != null && issue.details() != null)
      for (JsonNode row : json.readTree(issue.details()).path("listings"))
        previous.put(row.path("listing_id").asString(), row);
    List<JsonNode> drift = new ArrayList<>();
    Map<UUID, List<UUID>> repairs = new LinkedHashMap<>();
    String now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toString();
    for (Listing listing : listings) {
      Integer observed = seen.getOrDefault(listing.account(), Map.of()).get(listing.external());
      if (observed != null)
        jdbc.update(
            "UPDATE channel_listing SET last_seen_channel_qty = ? WHERE id = ?",
            observed,
            listing.id());
      if (!listing.controlled()) continue;
      JsonNode old = previous.get(listing.id().toString());
      if (observed == null || listing.pending()) {
        if (old != null) drift.add(old);
        continue;
      }
      if (listing.expected() == null || listing.expected().equals(observed)) continue;
      var row = json.createObjectNode();
      row.put("listing_id", listing.id().toString());
      row.put("channel_account_id", listing.account().toString());
      row.put("external_listing_id", listing.external());
      var skus = row.putArray("sku_ids");
      skus.add(listing.sku().toString());
      jdbc.query(
              "SELECT component_sku_id FROM sku_bundle_component WHERE bundle_sku_id = ? ORDER BY component_sku_id",
              (rs, n) -> rs.getString(1),
              listing.sku())
          .forEach(skus::add);
      row.put("expected", listing.expected());
      row.put("seen", observed);
      row.put("first_seen_at", old == null ? now : old.path("first_seen_at").asString(now));
      row.put("last_seen_at", now);
      row.put("repush_enqueued_at", now);
      drift.add(row);
      repairs.computeIfAbsent(listing.account(), k -> new ArrayList<>()).add(listing.id());
    }
    int events = 0;
    for (var repair : repairs.entrySet())
      events += pushes.repush(repair.getKey(), repair.getValue());
    var details = Map.of("listings", drift, "run_id", run.toString());
    String encoded = json.writeValueAsString(details);
    UUID id = issue == null ? UuidV7.generate() : issue.id();
    if (issue == null && !drift.isEmpty())
      jdbc.update(
          """
        INSERT INTO reconciliation_issue (id, tenant_id, run_id, rule, details)
        VALUES (?, ?, ?, 'STOCK_DRIFT', ?::jsonb)
        """,
          id,
          tenant,
          run,
          encoded);
    else if (issue != null)
      jdbc.update(
          """
        UPDATE reconciliation_issue SET run_id = ?, details = ?::jsonb,
          status = ?, updated_at = clock_timestamp() WHERE id = ?
        """,
          run,
          encoded,
          drift.isEmpty() ? "RESOLVED" : issue.status(),
          id);
    jdbc.update(
        """
        INSERT INTO audit_log (id, tenant_id, actor_type, actor_id, action, entity_type, entity_id, "after")
        VALUES (?, ?, ?, ?, 'STOCK_DRIFT_RUN', 'reconciliation_issue', ?, ?::jsonb)
        """,
        UuidV7.generate(),
        tenant,
        TenantContext.userId() == null ? "SYSTEM" : "USER",
        TenantContext.userId() == null ? null : TenantContext.userId().toString(),
        issue == null && drift.isEmpty() ? run.toString() : id.toString(),
        encoded);
    return new Result(run, drift.size(), events, false);
  }

  public List<Map<String, Object>> issues(String status) {
    return tx.execute(
        s ->
            jdbc.query(
                """
        SELECT id, status, details::text, created_at, updated_at FROM reconciliation_issue
        WHERE rule = 'STOCK_DRIFT' AND (?::text IS NULL OR status = ?)
        ORDER BY created_at DESC LIMIT 100
        """,
                (rs, n) ->
                    Map.<String, Object>of(
                        "id",
                        rs.getString(1),
                        "status",
                        rs.getString(2),
                        "details",
                        json.readTree(rs.getString(3)),
                        "created_at",
                        rs.getObject(4, OffsetDateTime.class),
                        "updated_at",
                        rs.getObject(5, OffsetDateTime.class)),
                status,
                status));
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
