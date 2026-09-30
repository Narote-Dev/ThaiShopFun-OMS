package com.thaishopfun.oms.warehouse;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.catalog.CatalogAccess;
import com.thaishopfun.oms.catalog.CatalogAudit;
import com.thaishopfun.oms.catalog.CatalogTransactions;
import com.thaishopfun.oms.catalog.SqlErrors;
import com.thaishopfun.oms.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Every tenant has exactly one default warehouse (V4 leaves that to T07). {@link #ensure} is
 * idempotent and safe under concurrent first calls: the insert is {@code ON CONFLICT DO NOTHING}
 * against {@code warehouse_one_default_per_tenant_idx} and {@code warehouse_tenant_code_key}, so
 * the loser simply reads the winner's row. Not tied to JIT provisioning.
 */
@Component
public class DefaultWarehouse {

  public static final String CODE = "MAIN";
  public static final String NAME = "Main warehouse";

  private final JdbcTemplate jdbc;
  private final CatalogTransactions tx;
  private final CatalogAudit audit;

  public DefaultWarehouse(JdbcTemplate jdbc, CatalogTransactions tx, CatalogAudit audit) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.audit = audit;
  }

  /** Returns the default warehouse id, creating {@code MAIN} when the tenant has none. */
  public UUID ensure() {
    // Step 1: Fast path. Almost every call finds the default and writes nothing.
    UUID existing = tx.read(this::findDefault);
    if (existing != null) {
      return existing;
    }
    CatalogAccess.Actor actor =
        new CatalogAccess.Actor(TenantContext.requireTenantId(), TenantContext.requireUserId());
    return tx.write(
        null,
        failure -> failure.is(SqlErrors.UNIQUE_VIOLATION, "warehouse_one_default_per_tenant_idx"),
        () -> {
          UUID current = findDefault();
          if (current != null) {
            return current;
          }
          // Step 2: Insert MAIN as default. A concurrent insert wins the unique index; we read it.
          UUID id = UuidV7.generate();
          List<UUID> inserted =
              jdbc.query(
                  """
                  INSERT INTO warehouse (id, tenant_id, code, name, is_default)
                  SELECT ?, ?, ?, ?, true
                  WHERE NOT EXISTS (SELECT 1 FROM warehouse WHERE is_default)
                  ON CONFLICT DO NOTHING
                  RETURNING id
                  """,
                  (rs, n) -> rs.getObject(1, UUID.class),
                  id,
                  actor.tenantId(),
                  CODE,
                  NAME);
          if (!inserted.isEmpty()) {
            audit.write(actor, "WAREHOUSE_CREATED", "warehouse", id, null, values(CODE, NAME));
            return id;
          }
          current = findDefault();
          if (current != null) {
            return current;
          }
          // Step 3: MAIN exists as a non-default row. Promote the oldest warehouse instead.
          List<UUID> promoted =
              jdbc.query(
                  """
                  UPDATE warehouse SET is_default = true, updated_at = now()
                  WHERE id = (SELECT id FROM warehouse ORDER BY created_at, id LIMIT 1)
                  RETURNING id
                  """,
                  (rs, n) -> rs.getObject(1, UUID.class));
          if (promoted.isEmpty()) {
            throw new IllegalStateException("no warehouse to promote");
          }
          audit.write(
              actor,
              "WAREHOUSE_DEFAULT_CHANGED",
              "warehouse",
              promoted.get(0),
              Map.of("default_warehouse_id", "none"),
              Map.of("default_warehouse_id", promoted.get(0).toString()));
          return promoted.get(0);
        });
  }

  private UUID findDefault() {
    List<UUID> rows =
        jdbc.query(
            "SELECT id FROM warehouse WHERE is_default", (rs, n) -> rs.getObject(1, UUID.class));
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static Map<String, Object> values(String code, String name) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("code", code);
    values.put("name", name);
    values.put("is_default", true);
    values.put("address_set", false);
    return values;
  }
}
