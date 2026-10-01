package com.thaishopfun.oms.stockdoc;

import com.thaishopfun.oms.auth.TraceIds;
import com.thaishopfun.oms.catalog.CatalogApiException;
import com.thaishopfun.oms.catalog.SqlErrors;
import com.thaishopfun.oms.stock.IdempotencyConflictException;
import com.thaishopfun.oms.stock.StockBusyException;
import com.thaishopfun.oms.stock.StockDocumentException;
import com.thaishopfun.oms.stock.StockOperationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Section 4.8 error body for the stock document and stock history controllers. A post or void
 * refused per line ({@code BELOW_RESERVED}, {@code REASON_REQUIRED}, ...) adds {@code errors}: one
 * entry per line or inventory row.
 */
@RestControllerAdvice(basePackages = "com.thaishopfun.oms.stockdoc")
class StockDocumentErrorHandler {

  private static final Logger log = LoggerFactory.getLogger(StockDocumentErrorHandler.class);

  @ExceptionHandler(CatalogApiException.class)
  ResponseEntity<Map<String, Object>> api(CatalogApiException ex, HttpServletRequest request) {
    return body(request, ex.status(), ex.code(), ex.getMessage(), List.of());
  }

  @ExceptionHandler(StockDocumentException.class)
  ResponseEntity<Map<String, Object>> document(
      StockDocumentException ex, HttpServletRequest request) {
    return body(
        request, ex.error().status(), ex.error().name(), message(ex), List.copyOf(ex.problems()));
  }

  @ExceptionHandler(StockOperationException.class)
  ResponseEntity<Map<String, Object>> stock(
      StockOperationException ex, HttpServletRequest request) {
    return body(request, ex.error().status(), ex.error().name(), message(ex), List.of());
  }

  @ExceptionHandler(StockBusyException.class)
  ResponseEntity<Map<String, Object>> busy(StockBusyException ex, HttpServletRequest request) {
    ResponseEntity<Map<String, Object>> response =
        body(request, 503, "STOCK_BUSY", "Stock is busy, try again", List.of());
    return ResponseEntity.status(503)
        .headers(response.getHeaders())
        .header("Retry-After", "1")
        .body(response.getBody());
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  ResponseEntity<Map<String, Object>> idempotency(
      IdempotencyConflictException ex, HttpServletRequest request) {
    return body(
        request, 409, "IDEMPOTENCY_CONFLICT", "Request conflicts with an earlier one", List.of());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, Object>> unreadable(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    return body(request, 422, "VALIDATION_FAILED", "Request body is invalid", List.of());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<Map<String, Object>> mismatch(
      MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
    // Step 1: A malformed id in the path is a record that does not exist.
    if (ex.getParameter().hasParameterAnnotation(PathVariable.class)) {
      return body(request, 404, "NOT_FOUND", "Not found", List.of());
    }
    return body(request, 422, "VALIDATION_FAILED", ex.getName() + " is invalid", List.of());
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<Map<String, Object>> missing(Exception ex, HttpServletRequest request) {
    return body(request, 422, "VALIDATION_FAILED", "A required parameter is missing", List.of());
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<Map<String, Object>> mediaType(
      HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
    return body(request, 415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported content type", List.of());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> unexpected(Exception ex, HttpServletRequest request) {
    // Step 2: The message can echo SQL parameters. Log the type and SQLSTATE only.
    SqlErrors.Failure failure = SqlErrors.failure(ex);
    log.warn(
        "stock document request failed: {} sqlstate={} constraint={}",
        ex.getClass().getSimpleName(),
        failure == null ? null : failure.sqlState(),
        failure == null ? null : failure.constraint());
    return body(request, 500, "INTERNAL_ERROR", "Unexpected error", List.of());
  }

  /** The engine prefixes its code ("BELOW_RESERVED: ..."); the body carries the code already. */
  private static String message(StockOperationException ex) {
    String text = ex.getMessage();
    String prefix = ex.error().name() + ": ";
    return text != null && text.startsWith(prefix) ? text.substring(prefix.length()) : text;
  }

  private static ResponseEntity<Map<String, Object>> body(
      HttpServletRequest request, int status, String code, String message, List<?> details) {
    String traceId = TraceIds.current(request);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", code);
    body.put("message", message);
    body.put("trace_id", traceId);
    if (!details.isEmpty()) {
      body.put("errors", details);
    }
    return ResponseEntity.status(status)
        .header("X-Trace-Id", traceId)
        .header("Cache-Control", "no-store")
        .body(body);
  }
}
