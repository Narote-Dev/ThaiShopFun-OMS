package com.thaishopfun.oms.stock;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * {@code on_hand} or {@code reserved} changed for these component SKUs. {@code bundleSkuIds} is
 * every bundle that uses one of them, so its derived availability changed too.
 *
 * <p>Published inside the engine transaction. Listen with {@code @TransactionalEventListener(phase
 * = AFTER_COMMIT)}: it is delivered after commit and dropped on rollback. Tenant id and SKU ids
 * only. Mapping it to outbox {@code stock.updated} is T15.
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
