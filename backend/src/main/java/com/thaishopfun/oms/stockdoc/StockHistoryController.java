package com.thaishopfun.oms.stockdoc;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StockHistoryController {

  private final StockHistoryService history;

  StockHistoryController(StockHistoryService history) {
    this.history = history;
  }

  @GetMapping("/api/v1/skus/{id}/stock-history")
  ResponseEntity<StockHistoryPage> history(
      @PathVariable UUID id,
      @RequestParam(name = "warehouse_id", required = false) UUID warehouseId,
      @RequestParam(name = "reason", required = false) String reason,
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) Integer limit) {
    return StockDocumentController.ok(
        history.history(id, warehouseId, reason, from, to, cursor, limit));
  }
}
