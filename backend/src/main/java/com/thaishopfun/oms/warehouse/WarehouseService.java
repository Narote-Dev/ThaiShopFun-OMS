package com.thaishopfun.oms.warehouse;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.catalog.CatalogAccess;
import com.thaishopfun.oms.catalog.CatalogApiException;
import com.thaishopfun.oms.catalog.CatalogAudit;
import com.thaishopfun.oms.catalog.CatalogTransactions;
import com.thaishopfun.oms.catalog.Fields;
import com.thaishopfun.oms.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Warehouses. Exactly one default per tenant; the default and referenced rows are not deleted. */
@Service
public class WarehouseService {

  static final int MAX_ADDRESS_JSON = 2000;

  private static final String SELECT =
      "SELECT id, code, name, address::text AS address, is_default, created_at, updated_at "
          + "FROM warehouse";

  private final JdbcTemplate jdbc;
  private final CatalogTransactions tx;
  private final CatalogAccess access;
  private final CatalogAudit audit;
  private final DefaultWarehouse defaults;
  private final JsonMapper json;

  public WarehouseService(
      JdbcTemplate jdbc,
      CatalogTransactions tx,
      CatalogAccess access,
      CatalogAudit audit,
      DefaultWarehouse defaults,
      JsonMapper json) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.access = access;
    this.audit = audit;
    this.defaults = defaults;
    this.json = json;
  }

  private record Input(String code, String name, String address, JsonNode addressNode) {}

  public List<WarehouseView> list() {
    // Step 1: The first list of a tenant creates MAIN. Every later call only reads.
    defaults.ensure();
    return tx.read(
        () -> jdbc.query(SELECT + " ORDER BY is_default DESC, code, id", (rs, n) -> map(rs)));
  }

  public WarehouseView get(UUID id) {
    return tx.read(() -> find(id));
  }

  public WarehouseView create(WarehouseRequest request) {
    CatalogAccess.Actor actor = access.requireWriter();
    Input input = validate(request);
    defaults.ensure();
    return tx.write(
        null,
        () -> {
          UUID id = UuidV7.generate();
          jdbc.update(
              "INSERT INTO warehouse (id, tenant_id, code, name, address, is_default) "
                  + "VALUES (?, ?, ?, ?, ?::jsonb, false)",
              id,
              TenantContext.requireTenantId(),
              input.code(),
              input.name(),
              input.address());
          WarehouseView created = find(id);
          audit.write(actor, "WAREHOUSE_CREATED", "warehouse", id, null, created.audit());
          return created;
        });
  }

  public WarehouseView update(UUID id, WarehouseRequest request) {
    CatalogAccess.Actor actor = access.requireWriter();
    Input input = validate(request);
    return tx.write(
        null,
        () -> {
          WarehouseView before = lock(id);
          if (before.code().equals(input.code())
              && before.name().equals(input.name())
              && Objects.equals(before.address(), input.addressNode())) {
            return before;
          }
          jdbc.update(
              "UPDATE warehouse SET code = ?, name = ?, address = ?::jsonb, updated_at = now() "
                  + "WHERE id = ?",
              input.code(),
              input.name(),
              input.address(),
              id);
          WarehouseView after = find(id);
          audit.write(actor, "WAREHOUSE_UPDATED", "warehouse", id, before.audit(), after.audit());
          return after;
        });
  }

  public WarehouseView setDefault(UUID id) {
    CatalogAccess.Actor actor = access.requireWriter();
    return tx.write(
        null,
        () -> {
          // Step 1: Lock all of the tenant's warehouses in id order. Locking only the current
          // default would let a concurrent switch commit a new default this statement cannot see.
          List<UUID> defaultsBefore = new ArrayList<>();
          List<UUID> locked = new ArrayList<>();
          jdbc.query(
              "SELECT id, is_default FROM warehouse ORDER BY id FOR UPDATE",
              rs -> {
                UUID row = rs.getObject("id", UUID.class);
                locked.add(row);
                if (rs.getBoolean("is_default")) {
                  defaultsBefore.add(row);
                }
              });
          if (!locked.contains(id)) {
            throw CatalogApiException.notFound("Warehouse");
          }
          if (defaultsBefore.contains(id)) {
            return find(id);
          }
          // Step 2: Unset first, then set. The partial unique index sees at most one default.
          jdbc.update(
              "UPDATE warehouse SET is_default = false, updated_at = now() "
                  + "WHERE is_default AND id <> ?",
              id);
          jdbc.update(
              "UPDATE warehouse SET is_default = true, updated_at = now() WHERE id = ?", id);
          audit.write(
              actor,
              "WAREHOUSE_DEFAULT_CHANGED",
              "warehouse",
              id,
              Map.of(
                  "default_warehouse_id",
                  defaultsBefore.isEmpty() ? "none" : defaultsBefore.get(0).toString()),
              Map.of("default_warehouse_id", id.toString()));
          return find(id);
        });
  }

  public void delete(UUID id) {
    CatalogAccess.Actor actor = access.requireWriter();
    tx.write(
        "WAREHOUSE_IN_USE",
        () -> {
          // Step 1: The default stays. Stock and document rows RESTRICT the delete (409).
          WarehouseView before = lock(id);
          if (before.isDefault()) {
            throw CatalogApiException.conflict(
                "WAREHOUSE_IS_DEFAULT", "Set another default before deleting this warehouse");
          }
          jdbc.update("DELETE FROM warehouse WHERE id = ?", id);
          audit.write(actor, "WAREHOUSE_DELETED", "warehouse", id, before.audit(), null);
          return null;
        });
  }

  private Input validate(WarehouseRequest request) {
    if (request == null) {
      throw CatalogApiException.invalid("Request body is required");
    }
    String code = Fields.trim(request.code());
    String name = Fields.trim(request.name());
    Fields.require("code", Fields.warehouseCodeError(code));
    Fields.require("name", Fields.nameError(name));
    JsonNode address = request.address();
    String addressJson = null;
    if (address != null && address.isNull()) {
      address = null;
    }
    if (address != null) {
      if (!address.isObject()) {
        throw CatalogApiException.invalid("address must be a JSON object");
      }
      addressJson = json.writeValueAsString(address);
      if (addressJson.length() > MAX_ADDRESS_JSON) {
        throw CatalogApiException.invalid("address is too long");
      }
    }
    return new Input(code, name, addressJson, address);
  }

  private WarehouseView lock(UUID id) {
    List<UUID> locked =
        jdbc.query(
            "SELECT id FROM warehouse WHERE id = ? FOR UPDATE",
            (rs, n) -> rs.getObject(1, UUID.class),
            id);
    if (locked.isEmpty()) {
      throw CatalogApiException.notFound("Warehouse");
    }
    return find(id);
  }

  private WarehouseView find(UUID id) {
    List<WarehouseView> rows = jdbc.query(SELECT + " WHERE id = ?", (rs, n) -> map(rs), id);
    if (rows.isEmpty()) {
      throw CatalogApiException.notFound("Warehouse");
    }
    return rows.get(0);
  }

  private WarehouseView map(ResultSet rs) throws SQLException {
    String address = rs.getString("address");
    OffsetDateTime created = rs.getObject("created_at", OffsetDateTime.class);
    OffsetDateTime updated = rs.getObject("updated_at", OffsetDateTime.class);
    return new WarehouseView(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        address == null ? null : json.readTree(address),
        rs.getBoolean("is_default"),
        created == null ? null : created.toInstant(),
        updated == null ? null : updated.toInstant());
  }
}
