package com.thaishopfun.oms.invariant;

import com.thaishopfun.oms.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Nightly invariant sweep for ACTIVE tenants (same scope as hold resolver /
 * list_active_tenant_ids).
 */
@Component
public class InvariantJob {

  public static final String VIOLATIONS_METRIC = "oms.invariant.violations";

  private static final Logger log = LoggerFactory.getLogger(InvariantJob.class);

  private final InvariantChecker checker;
  private final JdbcTemplate jdbc;
  private final MeterRegistry meters;

  public InvariantJob(InvariantChecker checker, JdbcTemplate jdbc, MeterRegistry meters) {
    this.checker = checker;
    this.jdbc = jdbc;
    this.meters = meters;
  }

  /** One full pass: schema once, then each ACTIVE tenant. */
  public int runOnce() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("invariant job must run outside a transaction");
    }
    UUID previousTenant = TenantContext.tenantId();
    UUID previousUser = TenantContext.userId();
    int violationCount = 0;
    try {
      TenantContext.clear();
      List<Violation> schema = checker.checkSchema();
      violationCount += emit(schema);
      List<UUID> tenants =
          jdbc.query(
              "SELECT id FROM list_active_tenant_ids()",
              (rs, row) -> rs.getObject("id", UUID.class));
      for (UUID tenantId : tenants) {
        try {
          violationCount += emit(checker.checkTenant(tenantId));
        } catch (Exception ex) {
          log.error("invariant check failed tenant_id={}", tenantId, ex);
          meters
              .counter(
                  VIOLATIONS_METRIC, List.of(Tag.of("code", InvariantCodes.CHECK_FAILED)))
              .increment();
        }
      }
      if (violationCount == 0) {
        log.info("invariant check complete violations=0 tenants={}", tenants.size());
      } else {
        log.error(
            "invariant check complete violations={} tenants={}", violationCount, tenants.size());
      }
      return violationCount;
    } finally {
      if (previousTenant != null) {
        TenantContext.set(previousTenant, previousUser);
      } else {
        TenantContext.clear();
      }
    }
  }

  private int emit(List<Violation> violations) {
    Map<String, Integer> byCode = new LinkedHashMap<>();
    for (Violation violation : violations) {
      byCode.merge(violation.code(), 1, Integer::sum);
      log.error(
          "invariant violation code={} tenant_id={} entity_ids={}",
          violation.code(),
          violation.tenantId(),
          violation.entityIds());
      meters.counter(VIOLATIONS_METRIC, List.of(Tag.of("code", violation.code()))).increment();
    }
    return violations.size();
  }
}
