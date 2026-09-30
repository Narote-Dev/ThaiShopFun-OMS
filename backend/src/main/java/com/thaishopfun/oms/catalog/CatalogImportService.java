package com.thaishopfun.oms.catalog;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.tenant.TenantContext;
import com.thaishopfun.oms.warehouse.DefaultWarehouse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * CSV catalog import, all-or-nothing. Every row is validated (shape first, then against the locked
 * database state) and every bad cell is reported; if any row fails nothing is written. Otherwise
 * products, SKUs and bundle components are upserted by {@code sku_code} in one READ COMMITTED
 * transaction with JDBC batches. Re-importing the same file changes nothing but the audit log.
 */
@Service
public class CatalogImportService {

  static final int MAX_ROWS = 20_000;
  static final List<String> COLUMNS =
      List.of(
          "product_name", "sku_code", "sku_name", "barcode", "weight_g", "is_bundle", "components");
  static final Set<String> REQUIRED_COLUMNS = Set.of("product_name", "sku_code", "sku_name");

  private static final Logger log = LoggerFactory.getLogger(CatalogImportService.class);

  private final JdbcTemplate jdbc;
  private final CatalogTransactions tx;
  private final CatalogAccess access;
  private final CatalogAudit audit;
  private final DefaultWarehouse defaults;

  public CatalogImportService(
      JdbcTemplate jdbc,
      CatalogTransactions tx,
      CatalogAccess access,
      CatalogAudit audit,
      DefaultWarehouse defaults) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.access = access;
    this.audit = audit;
    this.defaults = defaults;
  }

  /**
   * A row that passed the shape checks. An optional column that is absent from the header is "keep
   * the stored value" for an existing SKU: {@code hasBarcode}/{@code hasWeight} are false, {@code
   * bundle} is null, {@code components} is null. A present but empty cell clears the value. {@code
   * components} keeps file order.
   */
  record Row(
      int line,
      String productName,
      String code,
      String name,
      String barcode,
      boolean hasBarcode,
      Integer weight,
      boolean hasWeight,
      Boolean bundle,
      Map<String, Integer> components) {}

  record Parsed(List<Row> rows, List<ImportRowError> errors, Set<String> badCodes, int total) {}

  private record DbSku(
      UUID id,
      String code,
      UUID productId,
      String productName,
      String name,
      String barcode,
      Integer weight,
      boolean bundle) {}

  public ImportResult importCsv(byte[] content) {
    long started = System.nanoTime();
    // Step 1: Role check, then parse and shape-check every row without touching the database.
    CatalogAccess.Actor actor = access.requireWriter();
    Parsed parsed = parse(content);
    String sha256 = sha256(content);
    // Step 2: Validate against the locked rows and write, retrying a lost sku_code race.
    ImportResult result =
        tx.write(
            null,
            failure ->
                failure.is(SqlErrors.UNIQUE_VIOLATION, "sku_tenant_sku_code_key")
                    || failure.is(
                        SqlErrors.UNIQUE_VIOLATION, "warehouse_one_default_per_tenant_idx"),
            () -> apply(actor, parsed, sha256));
    long elapsed = (System.nanoTime() - started) / 1_000_000;
    log.info(
        "catalog import rows={} skus_created={} skus_updated={} skus_unchanged={} "
            + "bundles_replaced={} elapsed_ms={}",
        result.rows(),
        result.skusCreated(),
        result.skusUpdated(),
        result.skusUnchanged(),
        result.bundlesReplaced(),
        elapsed);
    return result.withElapsed(elapsed);
  }

  static Parsed parse(byte[] content) {
    List<CsvParser.Record> records;
    try {
      records = CsvParser.parse(content);
    } catch (CsvParser.CsvException ex) {
      throw invalid(List.of(new ImportRowError(ex.line(), null, ex.getMessage())));
    }
    if (records.isEmpty()) {
      throw invalid(List.of(new ImportRowError(1, null, "header row is required")));
    }

    // Step 1: Header. Known names only, each once, required ones present. Order is free.
    CsvParser.Record header = records.get(0);
    Map<String, Integer> index = new HashMap<>();
    List<ImportRowError> headerErrors = new ArrayList<>();
    for (int i = 0; i < header.fields().size(); i++) {
      String name = header.fields().get(i).strip().toLowerCase(Locale.ROOT);
      if (!COLUMNS.contains(name)) {
        headerErrors.add(new ImportRowError(header.line(), name, "unknown column"));
      } else if (index.put(name, i) != null) {
        headerErrors.add(new ImportRowError(header.line(), name, "duplicate column"));
      }
    }
    for (String required : COLUMNS) {
      if (REQUIRED_COLUMNS.contains(required) && !index.containsKey(required)) {
        headerErrors.add(new ImportRowError(header.line(), required, "column is required"));
      }
    }
    if (!headerErrors.isEmpty()) {
      throw invalid(headerErrors);
    }
    int total = records.size() - 1;
    if (total == 0) {
      throw invalid(List.of(new ImportRowError(header.line(), null, "the file has no data rows")));
    }
    if (total > MAX_ROWS) {
      throw invalid(
          List.of(
              new ImportRowError(header.line(), null, "at most " + MAX_ROWS + " rows per file")));
    }

    // Step 2: Each row on its own: required cells, formats, component syntax, duplicates.
    List<Row> rows = new ArrayList<>(total);
    List<ImportRowError> errors = new ArrayList<>();
    Set<String> badCodes = new HashSet<>();
    Map<String, Integer> firstLine = new HashMap<>();
    int width = header.fields().size();
    for (CsvParser.Record record : records.subList(1, records.size())) {
      int line = record.line();
      if (record.fields().size() != width) {
        errors.add(
            new ImportRowError(
                line, null, "expected " + width + " fields, found " + record.fields().size()));
        continue;
      }
      List<ImportRowError> rowErrors = new ArrayList<>();
      String productName = cell(record, index, "product_name");
      String code = cell(record, index, "sku_code");
      String name = cell(record, index, "sku_name");
      String barcode = cell(record, index, "barcode");
      check(rowErrors, line, "product_name", Fields.nameError(productName));
      check(rowErrors, line, "sku_code", Fields.skuCodeError(code));
      check(rowErrors, line, "sku_name", Fields.nameError(name));
      check(rowErrors, line, "barcode", Fields.barcodeError(barcode));
      Integer weight = null;
      String weightText = cell(record, index, "weight_g");
      if (weightText != null) {
        try {
          weight = Integer.valueOf(weightText);
          check(rowErrors, line, "weight_g", Fields.weightError(weight));
        } catch (NumberFormatException ex) {
          rowErrors.add(new ImportRowError(line, "weight_g", "must be a whole number"));
        }
      }
      Boolean bundle = null;
      if (index.containsKey("is_bundle")) {
        bundle = parseBoolean(cell(record, index, "is_bundle"));
        if (bundle == null) {
          rowErrors.add(new ImportRowError(line, "is_bundle", "must be true or false"));
        }
      }
      Map<String, Integer> components =
          index.containsKey("components") ? new LinkedHashMap<>() : null;
      String componentText = cell(record, index, "components");
      if (componentText != null) {
        if (Boolean.FALSE.equals(bundle)) {
          rowErrors.add(
              new ImportRowError(
                  line, "components", "BUNDLE_REQUIRED: components need is_bundle = true"));
        } else {
          parseComponents(componentText, line, components, rowErrors);
        }
      }
      if (code != null && Fields.skuCodeError(code) == null) {
        Integer first = firstLine.putIfAbsent(code, line);
        if (first != null) {
          rowErrors.add(
              new ImportRowError(
                  line, "sku_code", "duplicate sku_code (first on row " + first + ")"));
        }
      }
      if (rowErrors.isEmpty()) {
        rows.add(
            new Row(
                line,
                productName,
                code,
                name,
                barcode,
                index.containsKey("barcode"),
                weight,
                index.containsKey("weight_g"),
                bundle,
                components));
      } else {
        errors.addAll(rowErrors);
        if (code != null) {
          badCodes.add(code);
        }
      }
    }
    return new Parsed(rows, errors, badCodes, total);
  }

  private ImportResult apply(CatalogAccess.Actor actor, Parsed parsed, String sha256) {
    UUID tenantId = TenantContext.requireTenantId();
    List<ImportRowError> errors = new ArrayList<>(parsed.errors());
    Map<String, Row> fileRows = new LinkedHashMap<>();
    for (Row row : parsed.rows()) {
      fileRows.put(row.code(), row);
    }

    // Step 1: Lock every SKU the file names (rows and components) in id order.
    Set<String> codes = new LinkedHashSet<>(fileRows.keySet());
    for (Row row : parsed.rows()) {
      if (row.components() != null) {
        codes.addAll(row.components().keySet());
      }
    }
    Map<String, DbSku> db = lockByCode(codes);
    Map<UUID, DbSku> dbById = new HashMap<>();
    List<UUID> fileIds = new ArrayList<>();
    for (DbSku sku : db.values()) {
      dbById.put(sku.id(), sku);
      if (fileRows.containsKey(sku.code())) {
        fileIds.add(sku.id());
      }
    }

    // Step 2: Facts the rules need: current components, who uses the file SKUs, stock rows.
    // A bundle whose components column is absent keeps its list, so it counts as a user too.
    Set<String> replacingCodes = new HashSet<>();
    for (Row row : parsed.rows()) {
      if (row.components() != null) {
        replacingCodes.add(row.code());
      }
    }
    Map<UUID, Map<UUID, Integer>> currentComponents = componentsOf(fileIds);
    Map<UUID, List<String>> usedByOutsideBundles = usedByBundlesOutside(fileIds, replacingCodes);
    Set<UUID> stocked = stocked(fileIds);

    // Step 3: Effective is_bundle per file row: the cell, else the stored value, else false.
    Map<String, Boolean> finalBundle = new HashMap<>();
    for (Row row : parsed.rows()) {
      DbSku existing = db.get(row.code());
      finalBundle.put(
          row.code(), row.bundle() != null ? row.bundle() : existing != null && existing.bundle());
    }

    // Step 4: Cross-row and database rules, reported per cell like the shape checks.
    for (Row row : parsed.rows()) {
      DbSku existing = db.get(row.code());
      boolean bundle = finalBundle.get(row.code());
      Map<String, Integer> listed = row.components() == null ? Map.of() : row.components();
      if (row.bundle() == null && !listed.isEmpty() && !bundle) {
        errors.add(
            new ImportRowError(
                row.line(), "components", "BUNDLE_REQUIRED: components need is_bundle = true"));
      }
      if (row.components() == null
          && !bundle
          && existing != null
          && !currentComponents.getOrDefault(existing.id(), Map.of()).isEmpty()) {
        errors.add(
            new ImportRowError(
                row.line(),
                "is_bundle",
                "BUNDLE_REQUIRED: the SKU still has components; add an empty components column"
                    + " to clear them"));
      }
      for (String component : listed.keySet()) {
        if (component.equals(row.code())) {
          errors.add(
              new ImportRowError(
                  row.line(), "components", "NESTED_BUNDLE: a bundle cannot contain itself"));
          continue;
        }
        Row inFile = fileRows.get(component);
        DbSku inDb = db.get(component);
        if (inFile == null && inDb == null) {
          if (!parsed.badCodes().contains(component)) {
            errors.add(
                new ImportRowError(row.line(), "components", "unknown sku_code " + component));
          }
          continue;
        }
        boolean componentIsBundle = inFile != null ? finalBundle.get(component) : inDb.bundle();
        if (componentIsBundle) {
          errors.add(
              new ImportRowError(
                  row.line(),
                  "components",
                  "NESTED_BUNDLE: component " + component + " is a bundle"));
        }
      }
      if (bundle && existing != null) {
        if (stocked.contains(existing.id())) {
          errors.add(
              new ImportRowError(
                  row.line(), "is_bundle", "BUNDLE_NOT_STOCKABLE: the SKU has stock rows"));
        }
        List<String> users = usedByOutsideBundles.get(existing.id());
        if (users != null) {
          errors.add(
              new ImportRowError(
                  row.line(),
                  "is_bundle",
                  "NESTED_BUNDLE: the SKU is a component of bundle " + users.get(0)));
        }
      }
    }
    if (!errors.isEmpty()) {
      errors.sort(Comparator.comparingInt(ImportRowError::row));
      throw invalid(errors);
    }
    // The default warehouse is ensured inside this transaction, after validation, so a rejected
    // file writes nothing at all.
    defaults.ensure();

    // Step 5: Products by name. An existing SKU keeps its product when the name still matches.
    Set<String> neededNames = new LinkedHashSet<>();
    for (Row row : parsed.rows()) {
      DbSku existing = db.get(row.code());
      if (existing == null || !existing.productName().equals(row.productName())) {
        neededNames.add(row.productName());
      }
    }
    Map<String, UUID> productIds = productsByName(neededNames);
    List<CatalogAudit.Entry> audits = new ArrayList<>();
    List<Object[]> newProducts = new ArrayList<>();
    for (String name : neededNames) {
      if (!productIds.containsKey(name)) {
        UUID id = UuidV7.generate();
        productIds.put(name, id);
        newProducts.add(new Object[] {id, tenantId, name});
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", name);
        after.put("status", "ACTIVE");
        audits.add(new CatalogAudit.Entry("PRODUCT_CREATED", "product", id, null, after));
      }
    }

    // Step 6: SKU inserts and updates. Unchanged rows produce no write at all.
    List<SkuWrite> inserts = new ArrayList<>();
    List<SkuWrite> updates = new ArrayList<>();
    Map<String, UUID> idByCode = new HashMap<>();
    db.forEach((code, sku) -> idByCode.put(code, sku.id()));
    int unchanged = 0;
    for (Row row : parsed.rows()) {
      DbSku existing = db.get(row.code());
      UUID productId =
          existing != null && existing.productName().equals(row.productName())
              ? existing.productId()
              : productIds.get(row.productName());
      if (existing == null) {
        UUID id = UuidV7.generate();
        idByCode.put(row.code(), id);
        SkuWrite write =
            new SkuWrite(
                id,
                productId,
                row.code(),
                row.name(),
                row.barcode(),
                row.weight(),
                finalBundle.get(row.code()));
        inserts.add(write);
        audits.add(new CatalogAudit.Entry("SKU_CREATED", "sku", id, null, write.audit()));
        continue;
      }
      // Absent optional columns keep the stored values.
      SkuWrite write =
          new SkuWrite(
              existing.id(),
              productId,
              row.code(),
              row.name(),
              row.hasBarcode() ? row.barcode() : existing.barcode(),
              row.hasWeight() ? row.weight() : existing.weight(),
              finalBundle.get(row.code()));
      Map<String, Object> before = auditOf(existing);
      if (before.equals(write.audit())) {
        unchanged++;
      } else {
        updates.add(write);
        audits.add(
            new CatalogAudit.Entry("SKU_UPDATED", "sku", existing.id(), before, write.audit()));
      }
    }
    updates.sort(Comparator.comparing(SkuWrite::id));

    // Step 7: Component lists. Replace only the ones listed in the file that differ from the
    // database. An absent components column keeps the stored list.
    List<UUID> clearBundles = new ArrayList<>();
    List<UUID> touchBundles = new ArrayList<>();
    Set<UUID> updatedIds = new HashSet<>();
    for (SkuWrite write : updates) {
      updatedIds.add(write.id());
    }
    List<Object[]> componentRows = new ArrayList<>();
    int replaced = 0;
    for (Row row : parsed.rows()) {
      if (row.components() == null) {
        continue;
      }
      UUID bundleId = idByCode.get(row.code());
      Map<UUID, Integer> wanted = new LinkedHashMap<>();
      row.components().forEach((code, qty) -> wanted.put(idByCode.get(code), qty));
      Map<UUID, Integer> current = currentComponents.getOrDefault(bundleId, Map.of());
      if (wanted.equals(current)) {
        continue;
      }
      replaced++;
      if (!current.isEmpty()) {
        clearBundles.add(bundleId);
      }
      // Change: a component-only change still bumps the bundle's updated_at, as the REST path does.
      if (db.containsKey(row.code()) && !updatedIds.contains(bundleId)) {
        touchBundles.add(bundleId);
      }
      wanted.forEach(
          (componentId, qty) ->
              componentRows.add(new Object[] {tenantId, bundleId, componentId, qty}));
      audits.add(
          new CatalogAudit.Entry(
              "BUNDLE_COMPONENTS_REPLACED",
              "sku",
              bundleId,
              Map.of("components", componentAudit(current, dbById, idByCode)),
              Map.of("components", componentAudit(wanted, dbById, idByCode))));
    }

    // Step 8: Write in trigger-safe order: old components out, SKUs in or updated (is_bundle
    // flips see no stale component rows), then the new components.
    if (!newProducts.isEmpty()) {
      jdbc.batchUpdate(
          "INSERT INTO product (id, tenant_id, name, status) VALUES (?, ?, ?, 'ACTIVE')",
          newProducts);
    }
    if (!clearBundles.isEmpty()) {
      UUID[] array = clearBundles.toArray(UUID[]::new);
      jdbc.update(
          "DELETE FROM sku_bundle_component WHERE bundle_sku_id = ANY (?)",
          ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", array)));
    }
    if (!inserts.isEmpty()) {
      batch(
          """
          INSERT INTO sku (product_id, sku_code, name, barcode, weight_g, is_bundle, id, tenant_id)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?)
          """,
          inserts,
          tenantId);
    }
    if (!updates.isEmpty()) {
      batch(
          """
          UPDATE sku
          SET product_id = ?, sku_code = ?, name = ?, barcode = ?, weight_g = ?, is_bundle = ?,
              updated_at = now()
          WHERE id = ? AND tenant_id = ?
          """,
          updates,
          tenantId);
    }
    if (!componentRows.isEmpty()) {
      jdbc.batchUpdate(
          "INSERT INTO sku_bundle_component (tenant_id, bundle_sku_id, component_sku_id, qty) "
              + "VALUES (?, ?, ?, ?)",
          componentRows);
    }
    if (!touchBundles.isEmpty()) {
      touchBundles.sort(null);
      UUID[] array = touchBundles.toArray(UUID[]::new);
      jdbc.update(
          "UPDATE sku SET updated_at = now() WHERE id = ANY (?)",
          ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", array)));
    }

    // Step 9: Per-entity audit rows plus one summary row, all in this transaction.
    ImportResult result =
        new ImportResult(
            parsed.total(),
            newProducts.size(),
            inserts.size(),
            updates.size(),
            unchanged,
            replaced,
            0);
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("rows", result.rows());
    summary.put("products_created", result.productsCreated());
    summary.put("skus_created", result.skusCreated());
    summary.put("skus_updated", result.skusUpdated());
    summary.put("skus_unchanged", result.skusUnchanged());
    summary.put("bundles_replaced", result.bundlesReplaced());
    summary.put("file_sha256", sha256);
    audits.add(
        new CatalogAudit.Entry(
            "CATALOG_IMPORTED", "catalog_import", UuidV7.generate(), null, summary));
    audit.writeAll(actor, audits);
    return result;
  }

  private record SkuWrite(
      UUID id,
      UUID productId,
      String code,
      String name,
      String barcode,
      Integer weight,
      boolean bundle) {

    Map<String, Object> audit() {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("product_id", productId.toString());
      values.put("sku_code", code);
      values.put("name", name);
      values.put("barcode", barcode);
      values.put("weight_g", weight);
      values.put("is_bundle", bundle);
      return values;
    }
  }

  private void batch(String sql, List<SkuWrite> writes, UUID tenantId) {
    jdbc.batchUpdate(
        sql,
        new BatchPreparedStatementSetter() {
          @Override
          public void setValues(PreparedStatement ps, int i) throws SQLException {
            SkuWrite write = writes.get(i);
            ps.setObject(1, write.productId());
            ps.setString(2, write.code());
            ps.setString(3, write.name());
            if (write.barcode() == null) {
              ps.setNull(4, Types.VARCHAR);
            } else {
              ps.setString(4, write.barcode());
            }
            if (write.weight() == null) {
              ps.setNull(5, Types.INTEGER);
            } else {
              ps.setInt(5, write.weight());
            }
            ps.setBoolean(6, write.bundle());
            ps.setObject(7, write.id());
            ps.setObject(8, tenantId);
          }

          @Override
          public int getBatchSize() {
            return writes.size();
          }
        });
  }

  private Map<String, DbSku> lockByCode(Collection<String> codes) {
    Map<String, DbSku> found = new HashMap<>();
    if (codes.isEmpty()) {
      return found;
    }
    String[] array = codes.toArray(String[]::new);
    jdbc.query(
        """
        SELECT s.id, s.sku_code, s.product_id, p.name AS product_name, s.name, s.barcode,
               s.weight_g, s.is_bundle
        FROM sku s
        JOIN product p ON p.id = s.product_id
        WHERE s.sku_code = ANY (?)
        ORDER BY s.id
        FOR NO KEY UPDATE OF s
        """,
        ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", array)),
        rs -> {
          int weight = rs.getInt("weight_g");
          Integer weightG = rs.wasNull() ? null : weight;
          DbSku sku =
              new DbSku(
                  rs.getObject("id", UUID.class),
                  rs.getString("sku_code"),
                  rs.getObject("product_id", UUID.class),
                  rs.getString("product_name"),
                  rs.getString("name"),
                  rs.getString("barcode"),
                  weightG,
                  rs.getBoolean("is_bundle"));
          found.put(sku.code(), sku);
        });
    return found;
  }

  private Map<UUID, Map<UUID, Integer>> componentsOf(List<UUID> bundleIds) {
    Map<UUID, Map<UUID, Integer>> components = new HashMap<>();
    if (bundleIds.isEmpty()) {
      return components;
    }
    UUID[] array = bundleIds.toArray(UUID[]::new);
    jdbc.query(
        "SELECT bundle_sku_id, component_sku_id, qty FROM sku_bundle_component "
            + "WHERE bundle_sku_id = ANY (?)",
        ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", array)),
        rs -> {
          components
              .computeIfAbsent(
                  rs.getObject("bundle_sku_id", UUID.class), k -> new LinkedHashMap<>())
              .put(rs.getObject("component_sku_id", UUID.class), rs.getInt("qty"));
        });
    return components;
  }

  /** Component SKU id to the codes of bundles whose list this file does not replace. */
  private Map<UUID, List<String>> usedByBundlesOutside(List<UUID> ids, Set<String> replacingCodes) {
    Map<UUID, List<String>> used = new HashMap<>();
    if (ids.isEmpty()) {
      return used;
    }
    UUID[] array = ids.toArray(UUID[]::new);
    jdbc.query(
        """
        SELECT c.component_sku_id, b.sku_code
        FROM sku_bundle_component c
        JOIN sku b ON b.id = c.bundle_sku_id
        WHERE c.component_sku_id = ANY (?)
        ORDER BY b.sku_code
        """,
        ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", array)),
        rs -> {
          String bundleCode = rs.getString("sku_code");
          if (!replacingCodes.contains(bundleCode)) {
            used.computeIfAbsent(
                    rs.getObject("component_sku_id", UUID.class), k -> new ArrayList<>())
                .add(bundleCode);
          }
        });
    return used;
  }

  /** SKUs with an inventory or stock document row. T07 reads these tables, never writes them. */
  private Set<UUID> stocked(List<UUID> ids) {
    Set<UUID> stocked = new HashSet<>();
    if (ids.isEmpty()) {
      return stocked;
    }
    UUID[] array = ids.toArray(UUID[]::new);
    jdbc.query(
        """
        SELECT DISTINCT sku_id FROM inventory WHERE sku_id = ANY (?)
        UNION
        SELECT DISTINCT sku_id FROM stock_document_line WHERE sku_id = ANY (?)
        """,
        ps -> {
          ps.setArray(1, ps.getConnection().createArrayOf("uuid", array));
          ps.setArray(2, ps.getConnection().createArrayOf("uuid", array));
        },
        rs -> {
          stocked.add(rs.getObject("sku_id", UUID.class));
        });
    return stocked;
  }

  /** First product per name (oldest). Names are not unique in the schema. */
  private Map<String, UUID> productsByName(Collection<String> names) {
    Map<String, UUID> ids = new HashMap<>();
    if (names.isEmpty()) {
      return ids;
    }
    String[] array = names.toArray(String[]::new);
    jdbc.query(
        "SELECT id, name FROM product WHERE name = ANY (?) ORDER BY created_at, id",
        ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", array)),
        rs -> {
          ids.putIfAbsent(rs.getString("name"), rs.getObject("id", UUID.class));
        });
    return ids;
  }

  private static Map<String, Object> auditOf(DbSku sku) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("product_id", sku.productId().toString());
    values.put("sku_code", sku.code());
    values.put("name", sku.name());
    values.put("barcode", sku.barcode());
    values.put("weight_g", sku.weight());
    values.put("is_bundle", sku.bundle());
    return values;
  }

  private static List<Map<String, Object>> componentAudit(
      Map<UUID, Integer> components, Map<UUID, DbSku> dbById, Map<String, UUID> idByCode) {
    Map<UUID, String> codeById = new HashMap<>();
    idByCode.forEach((code, id) -> codeById.put(id, code));
    List<Map<String, Object>> list = new ArrayList<>();
    components.forEach(
        (id, qty) -> {
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("component_sku_id", id.toString());
          DbSku known = dbById.get(id);
          item.put("sku_code", known != null ? known.code() : codeById.get(id));
          item.put("qty", qty);
          list.add(item);
        });
    return list;
  }

  private static void parseComponents(
      String text, int line, Map<String, Integer> out, List<ImportRowError> errors) {
    String[] parts = text.split("\\|", -1);
    if (parts.length > Fields.MAX_COMPONENTS) {
      errors.add(
          new ImportRowError(
              line, "components", "at most " + Fields.MAX_COMPONENTS + " components"));
      return;
    }
    for (String part : parts) {
      String item = part.strip();
      int colon = item.lastIndexOf(':');
      if (colon <= 0 || colon == item.length() - 1) {
        errors.add(new ImportRowError(line, "components", "expected CODE:qty, got '" + item + "'"));
        return;
      }
      String code = item.substring(0, colon).strip();
      String qtyText = item.substring(colon + 1).strip();
      Integer qty;
      try {
        qty = Integer.valueOf(qtyText);
      } catch (NumberFormatException ex) {
        errors.add(new ImportRowError(line, "components", "qty for " + code + " must be a number"));
        return;
      }
      String codeError = Fields.skuCodeError(code);
      String qtyError = Fields.qtyError(qty);
      if (codeError != null) {
        errors.add(new ImportRowError(line, "components", "component sku_code " + codeError));
        return;
      }
      if (qtyError != null) {
        errors.add(new ImportRowError(line, "components", "qty for " + code + " " + qtyError));
        return;
      }
      if (out.put(code, qty) != null) {
        errors.add(
            new ImportRowError(line, "components", "component " + code + " is listed twice"));
        return;
      }
    }
  }

  private static Boolean parseBoolean(String text) {
    if (text == null) {
      return false;
    }
    return switch (text.toLowerCase(Locale.ROOT)) {
      case "true", "1", "yes", "y" -> true;
      case "false", "0", "no", "n" -> false;
      default -> null;
    };
  }

  private static String cell(CsvParser.Record record, Map<String, Integer> index, String column) {
    Integer position = index.get(column);
    return position == null ? null : Fields.trim(record.fields().get(position));
  }

  private static void check(List<ImportRowError> errors, int line, String column, String error) {
    if (error != null) {
      errors.add(new ImportRowError(line, column, error));
    }
  }

  static CatalogApiException invalid(List<ImportRowError> errors) {
    Set<Integer> rows = new LinkedHashSet<>();
    for (ImportRowError error : errors) {
      rows.add(error.row());
    }
    return new CatalogApiException(
        422,
        "IMPORT_INVALID",
        rows.size()
            + (rows.size() == 1 ? " row has" : " rows have")
            + " errors; nothing was imported",
        errors);
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
