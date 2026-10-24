package com.thaishopfun.oms.stockpush;

import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.outbox.OutboxAppender;
import com.thaishopfun.oms.outbox.OutboxDraft;
import com.thaishopfun.oms.stock.StockAvailability;
import com.thaishopfun.oms.stock.StockChanged;
import com.thaishopfun.oms.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class StockPushService {
  private final JdbcTemplate jdbc;
  private final StockAvailability availability;
  private final OutboxAppender outbox;
  private final StockPushProperties properties;
  private final MeterRegistry meters;
  private final JsonMapper json;
  private final TransactionTemplate tx;

  public StockPushService(
      JdbcTemplate jdbc,
      StockAvailability availability,
      OutboxAppender outbox,
      StockPushProperties properties,
      MeterRegistry meters,
      JsonMapper json,
      PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    this.availability = availability;
    this.outbox = outbox;
    this.properties = properties;
    this.meters = meters;
    this.json = json;
    tx = new TransactionTemplate(transactions);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(15);
  }

  /** AFTER_COMMIT: no stock locks. A crash before capture is repaired by the full scan. */
  public void capture(StockChanged change) {
    inTenant(
        change.tenantId(),
        () -> {
          String[] skus = change.allSkuIds().stream().map(UUID::toString).toArray(String[]::new);
          jdbc.update(
              """
          INSERT INTO stock_push_pending (tenant_id, channel_account_id, listing_id, dirty_since, changed_at)
          SELECT cl.tenant_id, cl.channel_account_id, cl.id, clock_timestamp(), coalesce((
            SELECT max(created_at) FROM inventory_ledger WHERE sku_id = ANY (?::uuid[])
          ), clock_timestamp())
          FROM channel_listing cl
          WHERE cl.sku_id = ANY (?::uuid[]) OR cl.sku_id IN (
            SELECT bundle_sku_id FROM sku_bundle_component WHERE component_sku_id = ANY (?::uuid[])
          )
          ORDER BY cl.id
          ON CONFLICT (tenant_id, listing_id) DO UPDATE
            SET dirty_since = coalesce(stock_push_pending.dirty_since, EXCLUDED.dirty_since),
                changed_at = least(stock_push_pending.changed_at, EXCLUDED.changed_at)
          """,
              ps -> {
                ps.setArray(1, ps.getConnection().createArrayOf("text", skus));
                ps.setArray(2, ps.getConnection().createArrayOf("text", skus));
                ps.setArray(3, ps.getConnection().createArrayOf("text", skus));
              });
          return null;
        });
  }

  private record Account(String mode, boolean paused, String status) {}

  private record Listing(
      UUID id,
      String external,
      String seller,
      String skuCode,
      boolean mapped,
      boolean removed,
      boolean controlled,
      Integer lastQty,
      Long lastVersion) {}

  private record State(
      String key, Integer qty, long version, OffsetDateTime dirty, OffsetDateTime changedAt) {}

  /** One account transaction; account lock serializes builders across JVMs and full scans. */
  public int flush(UUID tenant, UUID accountId, boolean full) {
    return inTenant(tenant, () -> build(accountId, full));
  }

  private int build(UUID accountId, boolean full) {
    List<Account> accounts =
        jdbc.query(
            """
        SELECT mode, stock_sync_paused, status FROM channel_account
        WHERE id = ? AND channel = 'TSF' FOR UPDATE
        """,
            (rs, n) -> new Account(rs.getString(1), rs.getBoolean(2), rs.getString(3)),
            accountId);
    if (accounts.isEmpty()) return 0;
    Account account = accounts.get(0);
    OffsetDateTime since =
        jdbc.queryForObject(
            """
        SELECT min(dirty_since) FROM stock_push_pending WHERE channel_account_id = ?
        """,
            OffsetDateTime.class,
            accountId);
    if (!full
        && (since == null
            || since.toInstant().plusMillis(properties.getDebounceMs()).isAfter(Instant.now())))
      return 0;
    if (account.paused()) count("skipped_paused", 1);
    boolean blocked =
        account.paused()
            || "DISCONNECTED".equals(account.status())
            || "OBSERVE".equals(account.mode());
    // Lock listings before pending rows, matching the mapping trigger. NO KEY UPDATE permits
    // concurrent FK checks. These are listing locks only; availability never locks inventory.
    List<Listing> listings =
        jdbc.query(
            """
        SELECT cl.id, cl.external_sku_id, cl.seller_sku, s.sku_code,
          cl.sku_id IS NOT NULL, cl.removed_at IS NOT NULL, cl.stock_control,
          cl.last_exposed_qty, cl.last_pushed_version
        FROM channel_listing cl LEFT JOIN sku s ON s.id = cl.sku_id
        WHERE cl.channel_account_id = ? AND (? OR EXISTS (
          SELECT 1 FROM stock_push_pending p WHERE p.listing_id = cl.id AND p.dirty_since IS NOT NULL
        )) ORDER BY cl.id FOR NO KEY UPDATE OF cl
        """,
            (rs, n) ->
                new Listing(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getBoolean(5),
                    rs.getBoolean(6),
                    rs.getBoolean(7),
                    rs.getObject(8, Integer.class),
                    rs.getObject(9, Long.class)),
            accountId,
            full);
    List<Map<String, Object>> items = new ArrayList<>();
    Instant changedAt = Instant.now();
    int emitted = 0;
    for (Listing listing : listings) {
      jdbc.update(
          """
          INSERT INTO stock_push_pending (tenant_id, channel_account_id, listing_id)
          VALUES (?, ?, ?) ON CONFLICT DO NOTHING
          """,
          TenantContext.requireTenantId(),
          accountId,
          listing.id());
      State state =
          jdbc.queryForObject(
              """
          SELECT source_key, observed_qty, stock_version, dirty_since, changed_at FROM stock_push_pending
          WHERE listing_id = ? FOR UPDATE
          """,
              (rs, n) ->
                  new State(
                      rs.getString(1),
                      rs.getObject(2, Integer.class),
                      rs.getLong(3),
                      rs.getObject(4, OffsetDateTime.class),
                      rs.getObject(5, OffsetDateTime.class)),
              listing.id());
      if (!blocked
          && listing.mapped()
          && !listing.removed()
          && (!"CONTROL".equals(account.mode()) || listing.controlled())) {
        // Read the signature BEFORE quantity. A concurrent stock commit is either represented
        // here or leaves a dirty marker after our pending-row lock is released.
        Source source = source(listing.id());
        String key = source.key();
        int qty = availability.channelExposed(listing.id());
        long version =
            nextVersion(state.key(), state.qty(), state.version(), listing.lastVersion(), key, qty);
        jdbc.update(
            """
            UPDATE stock_push_pending SET source_key = ?, observed_qty = ?, stock_version = ?
            WHERE listing_id = ?
            """,
            key,
            qty,
            version,
            listing.id());
        if ("SHADOW".equals(account.mode())) {
          if (state.dirty() != null
              || !Objects.equals(state.key(), key)
              || !Objects.equals(state.qty(), qty)) {
            jdbc.update(
                """
                INSERT INTO shadow_diff
                  (id, tenant_id, channel_account_id, kind, ref, oms_value, channel_value, observed_at)
                VALUES (?, ?, ?, 'STOCK_PUSH', ?, ?::jsonb, NULL, clock_timestamp())
                """,
                UuidV7.generate(),
                TenantContext.requireTenantId(),
                accountId,
                listing.external(),
                json.writeValueAsString(Map.of("available", qty, "stock_version", version)));
            count("shadow_records", 1);
          }
        } else if (!Objects.equals(listing.lastQty(), qty)
            || !Objects.equals(listing.lastVersion(), version)) {
          Instant itemOrigin =
              state.changedAt() == null ? source.changedAt() : state.changedAt().toInstant();
          if (itemOrigin != null && itemOrigin.isBefore(changedAt)) changedAt = itemOrigin;
          String seller = listing.seller();
          if (seller == null || seller.isBlank()) {
            seller = listing.skuCode();
            count("seller_sku_fallback", 1);
          }
          items.add(
              Map.of(
                  "listing_sku_id",
                  listing.external(),
                  "seller_sku",
                  seller,
                  "available",
                  qty,
                  "stock_version",
                  version));
          jdbc.update(
              """
              UPDATE channel_listing SET last_exposed_qty = ?, last_pushed_version = ? WHERE id = ?
              """,
              qty,
              version,
              listing.id());
          if (items.size() == properties.getBatchSize()) {
            append(accountId, items, changedAt);
            emitted++;
            items = new ArrayList<>();
            changedAt = Instant.now();
          }
        }
      }
      jdbc.update(
          "UPDATE stock_push_pending SET dirty_since = NULL, changed_at = NULL WHERE listing_id = ?",
          listing.id());
    }
    if (!items.isEmpty()) {
      append(accountId, items, changedAt);
      emitted++;
    }
    return emitted;
  }

  static long nextVersion(
      String oldKey, Integer oldQty, long version, Long pushed, String key, int qty) {
    long floor = Math.max(version, pushed == null ? 0 : pushed);
    return !Objects.equals(oldKey, key) || !Objects.equals(oldQty, qty)
        ? Math.addExact(floor, 1)
        : floor;
  }

  private record Source(String key, Instant changedAt) {}

  private Source source(UUID listingId) {
    return jdbc.queryForObject(
        """
        SELECT jsonb_build_array(cl.sku_id, p.mapping_revision,
          (SELECT jsonb_agg(jsonb_build_array(i.sku_id, i.warehouse_id, i.stock_version)
            ORDER BY i.sku_id, i.warehouse_id)
           FROM inventory i WHERE i.sku_id = cl.sku_id OR i.sku_id IN (
             SELECT component_sku_id FROM sku_bundle_component WHERE bundle_sku_id = cl.sku_id)),
          (SELECT jsonb_agg(jsonb_build_array(b.component_sku_id, b.qty) ORDER BY b.component_sku_id)
           FROM sku_bundle_component b WHERE b.bundle_sku_id = cl.sku_id))::text,
          (SELECT max(l.created_at) FROM inventory_ledger l
           WHERE l.sku_id = cl.sku_id OR l.sku_id IN (
             SELECT component_sku_id FROM sku_bundle_component WHERE bundle_sku_id = cl.sku_id))
        FROM channel_listing cl JOIN stock_push_pending p ON p.listing_id = cl.id WHERE cl.id = ?
        """,
        (rs, n) -> {
          OffsetDateTime changed = rs.getObject(2, OffsetDateTime.class);
          return new Source(rs.getString(1), changed == null ? null : changed.toInstant());
        },
        listingId);
  }

  private void append(UUID account, List<Map<String, Object>> items, Instant changedAt) {
    long version = items.stream().mapToLong(i -> (Long) i.get("stock_version")).max().orElseThrow();
    UUID id =
        outbox.append(
            OutboxDraft.of(
                "channel_account",
                account.toString(),
                "stock.updated",
                Map.of("items", items),
                version));
    jdbc.update(
        "UPDATE outbox_event SET stock_changed_at = ? WHERE id = ?",
        OffsetDateTime.ofInstant(changedAt, ZoneOffset.UTC),
        id);
    count("events", 1);
    count("items", items.size());
  }

  private void count(String name, int amount) {
    org.springframework.transaction.support.TransactionSynchronizationManager
        .registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
              @Override
              public void afterCommit() {
                meters.counter("oms.stock.push." + name).increment(amount);
              }
            });
  }

  private <T> T inTenant(UUID tenant, Supplier<T> work) {
    UUID previous = TenantContext.tenantId();
    UUID user = TenantContext.userId();
    TenantContext.set(tenant, null);
    try {
      return tx.execute(status -> work.get());
    } finally {
      if (previous == null) TenantContext.clear();
      else TenantContext.set(previous, user);
    }
  }
}
