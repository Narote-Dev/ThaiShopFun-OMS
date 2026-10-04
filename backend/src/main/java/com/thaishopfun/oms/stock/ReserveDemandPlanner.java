package com.thaishopfun.oms.stock;

import com.thaishopfun.oms.stock.StockRepository.Component;
import com.thaishopfun.oms.stock.StockRepository.SkuInfo;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Read-only bundle expansion and per-component demand (same rules as {@link ReservationEngine}).
 */
@Service
public class ReserveDemandPlanner {

  private final StockRepository repository;

  ReserveDemandPlanner(StockRepository repository) {
    this.repository = repository;
  }

  public record ComponentDemandPlan(
      Map<UUID, Integer> demand, Set<UUID> componentlessBundleSkuIds) {}

  /** Bundle SKUs flagged {@code is_bundle} with no component rows (catalog may be mid-edit). */
  public Set<UUID> componentlessBundleSkus(Collection<UUID> skuIds) {
    if (skuIds == null || skuIds.isEmpty()) {
      return Set.of();
    }
    Map<UUID, SkuInfo> skus = repository.skus(skuIds);
    Set<UUID> componentless = new LinkedHashSet<>();
    for (UUID skuId : skuIds) {
      SkuInfo sku = skus.get(skuId);
      if (sku != null && sku.bundle() && sku.components().isEmpty()) {
        componentless.add(skuId);
      }
    }
    return componentless;
  }

  public ComponentDemandPlan componentDemandPlan(List<ReserveItem> items) {
    if (items == null || items.isEmpty()) {
      return new ComponentDemandPlan(Map.of(), Set.of());
    }
    Set<UUID> skuIds = new LinkedHashSet<>();
    for (ReserveItem item : items) {
      skuIds.add(item.skuId());
    }
    Set<UUID> componentless = componentlessBundleSkus(skuIds);
    Map<UUID, SkuInfo> skus = repository.skus(skuIds);
    UUID defaultWarehouse = repository.defaultWarehouse();
    Map<UUID, Integer> demand = new LinkedHashMap<>();
    Map<UUID, UUID> warehouseBySku = new LinkedHashMap<>();
    for (ReserveItem item : items) {
      if (componentless.contains(item.skuId())) {
        continue;
      }
      UUID warehouseId = item.warehouseId() == null ? defaultWarehouse : item.warehouseId();
      SkuInfo sku = skus.get(item.skuId());
      if (sku == null) {
        throw new StockOperationException(
            StockError.UNKNOWN_SKU, "unknown sku " + item.skuId(), item.skuId());
      }
      List<Component> parts = sku.bundle() ? sku.components() : List.of(new Component(sku.id(), 1));
      for (Component part : parts) {
        UUID previous = warehouseBySku.putIfAbsent(part.skuId(), warehouseId);
        if (previous != null && !previous.equals(warehouseId)) {
          throw new IllegalArgumentException(
              "sku " + part.skuId() + " is requested from two warehouses");
        }
        int lineQty;
        try {
          lineQty = Math.multiplyExact(item.qty(), part.qty());
        } catch (ArithmeticException ex) {
          throw new IllegalArgumentException("quantity is too large", ex);
        }
        demand.merge(
            part.skuId(),
            lineQty,
            (left, right) -> {
              try {
                return Math.addExact(left, right);
              } catch (ArithmeticException ex) {
                throw new IllegalArgumentException("quantity is too large", ex);
              }
            });
      }
    }
    return new ComponentDemandPlan(demand, componentless);
  }

  /**
   * Expands bundles and sums requested units per component SKU. Throws {@link
   * IllegalArgumentException} when quantities overflow or a component SKU is requested from two
   * warehouses. Componentless bundles contribute no demand.
   */
  public Map<UUID, Integer> componentDemand(List<ReserveItem> items) {
    return componentDemandPlan(items).demand();
  }
}
