package com.thaishopfun.oms.stock;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * {@code on_hand} or {@code reserved} changed for these component SKUs. {@code bundleSkuIds} is
 * every bundle that uses one of them, so its derived availability changed too.
 *
 * <p>Published inside the engine transaction. Durable stock-push capture listens with
 * {@code @TransactionalEventListener(phase = BEFORE_COMMIT)} and joins that transaction, so stock
 * and its dirty marker commit or roll back together without borrowing another connection.
 * Observational listeners can use {@code AFTER_COMMIT}. Tenant id and SKU ids only.
 */
public record StockChanged(UUID tenantId, Set<UUID> componentSkuIds, Set<UUID> bundleSkuIds) {

  public StockChanged {
    componentSkuIds = Set.copyOf(componentSkuIds);
    bundleSkuIds = Set.copyOf(bundleSkuIds);
  }

  public Set<UUID> allSkuIds() {
    Set<UUID> all = new HashSet<>(componentSkuIds);
    all.addAll(bundleSkuIds);
    return Set.copyOf(all);
  }
}
