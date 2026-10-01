package com.thaishopfun.oms.stock;

import com.thaishopfun.oms.stock.StockRepository.Component;
import com.thaishopfun.oms.stock.StockRepository.SkuInfo;
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

  /**
   * Expands bundles and sums requested units per component SKU. Throws {@link
   * IllegalArgumentException} when quantities overflow or a component SKU is requested from two
   * warehouses.
   */
  public Map<UUID, Integer> componentDemand(List<ReserveItem> items) {
    if (items == null || items.isEmpty()) {
      return Map.of();
    }
    Set<UUID> skuIds = new LinkedHashSet<>();
    for (ReserveItem item : items) {
      skuIds.add(item.skuId());
    }
    Map<UUID, SkuInfo> skus = repository.skus(skuIds);
    UUID defaultWarehouse = repository.defaultWarehouse();
    Map<UUID, Integer> demand = new LinkedHashMap<>();
    Map<UUID, UUID> warehouseBySku = new LinkedHashMap<>();
    for (ReserveItem item : items) {
      UUID warehouseId = item.warehouseId() == null ? defaultWarehouse : item.warehouseId();
      SkuInfo sku = skus.get(item.skuId());
      if (sku == null) {
        throw new StockOperationException(StockError.UNKNOWN_SKU, "unknown sku " + item.skuId());
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
    return demand;
  }
}
