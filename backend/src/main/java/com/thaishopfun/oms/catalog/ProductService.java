package com.thaishopfun.oms.catalog;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Products. Archive sets INACTIVE. A product with SKUs is never hard-deleted. */
@Service
public class ProductService {

  static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");

  private static final String SELECT =
      """
      SELECT p.id, p.name, p.status, p.created_at, p.updated_at,
             (SELECT count(*) FROM sku s WHERE s.product_id = p.id) AS sku_count
      FROM product p
      """;

  private final JdbcTemplate jdbc;
  private final CatalogTransactions tx;
  private final CatalogAccess access;
  private final CatalogAudit audit;

  public ProductService(
      JdbcTemplate jdbc, CatalogTransactions tx, CatalogAccess access, CatalogAudit audit) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.access = access;
    this.audit = audit;
  }

  public ProductView create(ProductRequest request) {
    // Step 1: Validate, then insert and audit in one transaction.
    CatalogAccess.Actor actor = access.requireWriter();
    String name = Fields.trim(request == null ? null : request.name());
    Fields.require("name", Fields.nameError(name));
    String status = status(request.status(), "ACTIVE");
    return tx.write(
        null,
        () -> {
          UUID id = insert(actor, name, status);
          return find(id);
        });
  }

  /** Inserts one product and its audit row. Caller holds the transaction. */
  UUID insert(CatalogAccess.Actor actor, String name, String status) {
    UUID id = UuidV7.generate();
    jdbc.update(
        "INSERT INTO product (id, tenant_id, name, status) VALUES (?, ?, ?, ?)",
        id,
        TenantContext.requireTenantId(),
        name,
        status);
    audit.write(
        actor,
        "PRODUCT_CREATED",
        "product",
        id,
        null,
        new ProductView(id, name, status, 0, null, null).audit());
    return id;
  }

  public PageResult<ProductView> list(String q, String status, Integer limit, Integer offset) {
    int pageLimit = PageResult.limit(limit);
    int pageOffset = PageResult.offset(offset);
    String query = Fields.trim(q);
    String statusFilter = Fields.trim(status);
    if (statusFilter != null && !STATUSES.contains(statusFilter)) {
      throw CatalogApiException.invalid("status must be ACTIVE or INACTIVE");
    }
    // Step 1: Name contains q (case-insensitive). Stable order: name, then id.
    StringBuilder where = new StringBuilder(" WHERE true");
    List<Object> params = new ArrayList<>();
    if (query != null) {
      where.append(" AND p.name ILIKE ? ESCAPE '\\'");
      params.add("%" + Fields.likeEscape(query) + "%");
    }
    if (statusFilter != null) {
      where.append(" AND p.status = ?");
      params.add(statusFilter);
    }
    return tx.read(
        () -> {
          Long total =
              jdbc.queryForObject(
                  "SELECT count(*) FROM product p" + where, Long.class, params.toArray());
          List<Object> pageParams = new ArrayList<>(params);
          pageParams.add(pageLimit);
          pageParams.add(pageOffset);
          List<ProductView> items =
              jdbc.query(
                  SELECT + where + " ORDER BY p.name, p.id LIMIT ? OFFSET ?",
                  (rs, n) -> map(rs),
                  pageParams.toArray());
          return new PageResult<>(items, total == null ? 0 : total, pageLimit, pageOffset);
        });
  }

  public ProductView get(UUID id) {
    return tx.read(() -> find(id));
  }

  public ProductView update(UUID id, ProductRequest request) {
    CatalogAccess.Actor actor = access.requireWriter();
    String name = Fields.trim(request == null ? null : request.name());
    Fields.require("name", Fields.nameError(name));
    return tx.write(
        null,
        () -> {
          // Step 1: Lock the row, keep the old values for the audit.
          ProductView before = lock(id);
          String status = status(request.status(), before.status());
          if (before.name().equals(name) && before.status().equals(status)) {
            return before;
          }
          jdbc.update(
              "UPDATE product SET name = ?, status = ?, updated_at = now() WHERE id = ?",
              name,
              status,
              id);
          ProductView after = find(id);
          audit.write(actor, "PRODUCT_UPDATED", "product", id, before.audit(), after.audit());
          return after;
        });
  }

  public ProductView archive(UUID id) {
    CatalogAccess.Actor actor = access.requireWriter();
    return tx.write(
        null,
        () -> {
          ProductView before = lock(id);
          if ("INACTIVE".equals(before.status())) {
            return before;
          }
          jdbc.update(
              "UPDATE product SET status = 'INACTIVE', updated_at = now() WHERE id = ?", id);
          ProductView after = find(id);
          audit.write(actor, "PRODUCT_ARCHIVED", "product", id, before.audit(), after.audit());
          return after;
        });
  }

  public void delete(UUID id) {
    CatalogAccess.Actor actor = access.requireWriter();
    tx.write(
        "PRODUCT_IN_USE",
        () -> {
          // Step 1: Refuse while SKUs exist. The RESTRICT foreign key backs this up.
          ProductView before = lock(id);
          if (before.skuCount() > 0) {
            throw CatalogApiException.conflict(
                "PRODUCT_IN_USE", "A product with SKUs cannot be deleted; archive it instead");
          }
          jdbc.update("DELETE FROM product WHERE id = ?", id);
          audit.write(actor, "PRODUCT_DELETED", "product", id, before.audit(), null);
          return null;
        });
  }

  ProductView find(UUID id) {
    List<ProductView> rows = jdbc.query(SELECT + " WHERE p.id = ?", (rs, n) -> map(rs), id);
    if (rows.isEmpty()) {
      throw CatalogApiException.notFound("Product");
    }
    return rows.get(0);
  }

  private ProductView lock(UUID id) {
    List<UUID> locked =
        jdbc.query(
            "SELECT id FROM product WHERE id = ? FOR NO KEY UPDATE",
            (rs, n) -> rs.getObject(1, UUID.class),
            id);
    if (locked.isEmpty()) {
      throw CatalogApiException.notFound("Product");
    }
    return find(id);
  }

  private static String status(String requested, String fallback) {
    String value = Fields.trim(requested);
    if (value == null) {
      return fallback;
    }
    if (!STATUSES.contains(value)) {
      throw CatalogApiException.invalid("status must be ACTIVE or INACTIVE");
    }
    return value;
  }

  static ProductView map(ResultSet rs) throws SQLException {
    return new ProductView(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getString("status"),
        rs.getLong("sku_count"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
