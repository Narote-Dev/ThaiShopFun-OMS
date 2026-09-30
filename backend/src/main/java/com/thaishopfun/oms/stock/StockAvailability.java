package com.thaishopfun.oms.stock;

import com.thaishopfun.oms.stock.StockRepository.Component;
import com.thaishopfun.oms.stock.StockRepository.InventoryRow;
import com.thaishopfun.oms.stock.StockRepository.ListingRow;
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
 * Availability reads. No row locks: a value can be stale by the time the caller uses it, which is
 * fine for display and stock push. Reserve re-checks under the lock.
 *
 * <ul>
 *   <li>{@code physical_available = on_hand - reserved} per SKU and warehouse (0 with no row).
 *   <li>Bundle: {@code min(floor(avail(component) / qty))} over its components, 0 if any component
 *       has no inventory row.
 *   <li>{@code channel_exposed = max(0, available - channel_listing.safety_buffer)}, default
 *       warehouse, 0 for an unmapped listing.
 * </ul>
 */
@Service
public class StockAvailability {

  private final StockTransactions transactions;
  private final StockRepository repository;

  StockAvailability(StockTransactions transactions, StockRepository repository) {
    this.transactions = transactions;
    this.repository = repository;
  }

  /** Available units of a plain or bundle SKU. A null warehouse means the default warehouse. */
  public int available(UUID skuId, UUID warehouseId) {
    return available(List.of(skuId), warehouseId).get(skuId);
  }

  /** Same as {@link #available(UUID, UUID)} for several SKUs in one read. */
  public Map<UUID, Integer> available(Collection<UUID> skuIds, UUID warehouseId) {
    if (skuIds == null || skuIds.isEmpty()) {
      throw new IllegalArgumentException("skuIds are required");
    }
    return transactions.read(() -> availableInTx(new LinkedHashSet<>(skuIds), warehouseId));
  }

  /** What a channel listing may show. Unmapped listings expose 0. */
  public int channelExposed(UUID listingId) {
    if (listingId == null) {
      throw new IllegalArgumentException("listingId is required");
    }
    return transactions.read(
        () -> {
          // Step 1: Unknown or unmapped listing exposes nothing.
          ListingRow listing = repository.listing(listingId);
          if (listing == null) {
            throw new StockOperationException(
                StockError.UNKNOWN_SKU, "listing " + listingId + " does not exist");
          }
          if (listing.skuId() == null) {
            return 0;
          }
          // Step 2: Availability in the default warehouse, minus the listing's buffer.
          int available = availableInTx(Set.of(listing.skuId()), null).get(listing.skuId());
          return Math.max(0, available - listing.safetyBuffer());
        });
  }

  private Map<UUID, Integer> availableInTx(Set<UUID> skuIds, UUID warehouseId) {
    // Step 1: Warehouse.
    UUID warehouse = warehouseId;
    if (warehouse == null) {
      warehouse = repository.defaultWarehouse();
      if (warehouse == null) {
        throw new StockOperationException(
            StockError.NO_DEFAULT_WAREHOUSE, "tenant has no default warehouse");
      }
    }
    // Step 2: SKUs and their components. Unknown ids fail the whole read.
    Map<UUID, SkuInfo> skus = repository.skus(skuIds);
    Set<UUID> stockSkus = new LinkedHashSet<>();
    for (UUID skuId : skuIds) {
      SkuInfo sku = skus.get(skuId);
      if (sku == null) {
        throw new StockOperationException(StockError.UNKNOWN_SKU, "unknown sku " + skuId);
      }
      if (sku.bundle()) {
        sku.components().forEach(component -> stockSkus.add(component.skuId()));
      } else {
        stockSkus.add(skuId);
      }
    }
    // Step 3: One inventory read, then derive plain and bundle values.
    Map<UUID, InventoryRow> rows = repository.inventoryInWarehouse(warehouse, stockSkus);
    Map<UUID, Integer> result = new LinkedHashMap<>();
    for (UUID skuId : skuIds) {
      SkuInfo sku = skus.get(skuId);
      result.put(skuId, sku.bundle() ? bundle(sku, rows) : physical(rows.get(skuId)));
    }
    return result;
  }

  private static int physical(InventoryRow row) {
    return row == null ? 0 : Math.max(0, row.available());
  }

  private static int bundle(SkuInfo bundle, Map<UUID, InventoryRow> rows) {
    if (bundle.components().isEmpty()) {
      return 0;
    }
    int units = Integer.MAX_VALUE;
    for (Component component : bundle.components()) {
      InventoryRow row = rows.get(component.skuId());
      if (row == null) {
        return 0;
      }
      units = Math.min(units, physical(row) / component.qty());
    }
    return units;
  }
}
