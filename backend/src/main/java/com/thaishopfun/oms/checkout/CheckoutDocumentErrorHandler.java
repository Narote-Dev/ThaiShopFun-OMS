package com.thaishopfun.oms.checkout;

import com.thaishopfun.oms.auth.TraceIds;
import com.thaishopfun.oms.catalog.SqlErrors;
import com.thaishopfun.oms.stock.IdempotencyConflictException;
import com.thaishopfun.oms.stock.StockBusyException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.thaishopfun.oms.checkout")
class CheckoutDocumentErrorHandler {

  private static final Logger log = LoggerFactory.getLogger(CheckoutDocumentErrorHandler.class);

  @ExceptionHandler(CheckoutBadRequestException.class)
  ResponseEntity<Map<String, Object>> badRequest(
      CheckoutBadRequestException ex, HttpServletRequest request) {
    return body(request, 400, "BAD_REQUEST", ex.getMessage(), List.of());
  }

  @ExceptionHandler(CheckoutOutOfStockException.class)
  ResponseEntity<Map<String, Object>> outOfStock(
      CheckoutOutOfStockException ex, HttpServletRequest request) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("error", "OUT_OF_STOCK");
    payload.put(
        "items",
        ex.items().stream()
            .map(
                item ->
                    Map.of(
                        "listing_sku_id",
                        item.listingSkuId(),
                        "requested",
                        item.requested(),
                        "available",
                        item.available()))
            .toList());
    return ResponseEntity.status(409)
        .header("X-Trace-Id", TraceIds.current(request))
        .header("Cache-Control", "no-store")
        .body(payload);
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  ResponseEntity<Map<String, Object>> idempotency(
      IdempotencyConflictException ex, HttpServletRequest request) {
    return body(
        request, 409, "IDEMPOTENCY_CONFLICT", "Request conflicts with an earlier one", List.of());
  }

  @ExceptionHandler(StockBusyException.class)
  ResponseEntity<Map<String, Object>> busy(StockBusyException ex, HttpServletRequest request) {
    return ResponseEntity.status(503)
        .header("Retry-After", "1")
        .header("X-Trace-Id", TraceIds.current(request))
        .body(
            Map.of(
                "error",
                "STOCK_BUSY",
                "message",
                "Stock is busy, try again",
                "trace_id",
                TraceIds.current(request)));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, Object>> unreadable(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    return body(request, 400, "BAD_REQUEST", "Request body is invalid", List.of());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> unexpected(Exception ex, HttpServletRequest request) {
    if (CheckoutTimeouts.isStockBusyTimeout(ex)) {
      return busy(new StockBusyException("transaction_timeout", ex), request);
    }
    SqlErrors.Failure failure = SqlErrors.failure(ex);
    log.warn(
        "checkout request failed: {} sqlstate={} constraint={}",
        ex.getClass().getSimpleName(),
        failure == null ? null : failure.sqlState(),
        failure == null ? null : failure.constraint());
    return body(request, 500, "INTERNAL_ERROR", "Unexpected error", List.of());
  }

  private static ResponseEntity<Map<String, Object>> body(
      HttpServletRequest request, int status, String code, String message, List<?> details) {
    String traceId = TraceIds.current(request);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("error", code);
    payload.put("message", message);
    payload.put("trace_id", traceId);
    if (!details.isEmpty()) {
      payload.put("errors", details);
    }
    return ResponseEntity.status(status)
        .header("X-Trace-Id", traceId)
        .header("Cache-Control", "no-store")
        .body(payload);
  }
}
