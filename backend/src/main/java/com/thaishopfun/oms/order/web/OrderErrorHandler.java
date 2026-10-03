package com.thaishopfun.oms.order.web;

import com.thaishopfun.oms.auth.TraceIds;
import com.thaishopfun.oms.catalog.CatalogApiException;
import com.thaishopfun.oms.catalog.SqlErrors;
import com.thaishopfun.oms.channel.exception.ChannelClientException;
import com.thaishopfun.oms.channel.exception.ChannelIdempotencyConflictException;
import com.thaishopfun.oms.channel.exception.ChannelRateLimitedException;
import com.thaishopfun.oms.channel.exception.ChannelServerErrorException;
import com.thaishopfun.oms.channel.exception.ChannelUnavailableException;
import com.thaishopfun.oms.order.OrderOptimisticLockException;
import com.thaishopfun.oms.order.OrderStateException;
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

@RestControllerAdvice(basePackages = "com.thaishopfun.oms.order.web")
class OrderErrorHandler {

  private static final Logger log = LoggerFactory.getLogger(OrderErrorHandler.class);

  @ExceptionHandler(OrderApiException.class)
  ResponseEntity<Map<String, Object>> order(OrderApiException ex, HttpServletRequest request) {
    return body(request, ex.status(), ex.code(), ex.getMessage(), ex.details());
  }

  @ExceptionHandler(ChannelIdempotencyConflictException.class)
  ResponseEntity<Map<String, Object>> idempotencyConflict(
      ChannelIdempotencyConflictException ex, HttpServletRequest request) {
    return body(
        request,
        409,
        "CANCEL_REQUEST_CONFLICT",
        "A cancel request with a different reason was already sent for this order",
        List.of());
  }

  @ExceptionHandler({OrderStateException.class, OrderOptimisticLockException.class})
  ResponseEntity<Map<String, Object>> stateConflict(
      RuntimeException ex, HttpServletRequest request) {
    return body(request, 409, "ORDER_NOT_CANCELLABLE", ex.getMessage(), List.of());
  }

  @ExceptionHandler(CatalogApiException.class)
  ResponseEntity<Map<String, Object>> catalog(CatalogApiException ex, HttpServletRequest request) {
    return body(request, ex.status(), ex.code(), ex.getMessage(), List.of());
  }

  @ExceptionHandler(ChannelRateLimitedException.class)
  ResponseEntity<Map<String, Object>> rateLimited(
      ChannelRateLimitedException ex, HttpServletRequest request) {
    ResponseEntity<Map<String, Object>> response =
        body(request, 429, "CHANNEL_RATE_LIMITED", "Channel is rate limiting requests", List.of());
    if (ex.retryAfterSeconds() != null && ex.retryAfterSeconds() > 0) {
      return ResponseEntity.status(429)
          .headers(response.getHeaders())
          .header("Retry-After", String.valueOf(ex.retryAfterSeconds()))
          .body(response.getBody());
    }
    return response;
  }

  @ExceptionHandler(ChannelUnavailableException.class)
  ResponseEntity<Map<String, Object>> unavailable(
      ChannelUnavailableException ex, HttpServletRequest request) {
    return body(
        request, 503, "CHANNEL_UNAVAILABLE", "Channel is temporarily unavailable", List.of());
  }

  @ExceptionHandler(ChannelClientException.class)
  ResponseEntity<Map<String, Object>> client(
      ChannelClientException ex, HttpServletRequest request) {
    return body(request, 502, "CHANNEL_ERROR", "Channel rejected the request", List.of());
  }

  @ExceptionHandler(ChannelServerErrorException.class)
  ResponseEntity<Map<String, Object>> server(
      ChannelServerErrorException ex, HttpServletRequest request) {
    return body(request, 502, "CHANNEL_ERROR", "Channel returned an error", List.of());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, Object>> unreadable(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    return body(request, 422, "VALIDATION_FAILED", "Request body is invalid", List.of());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<Map<String, Object>> mismatch(
      MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
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
    SqlErrors.Failure failure = SqlErrors.failure(ex);
    log.warn(
        "orders request failed: {} sqlstate={} constraint={}",
        ex.getClass().getSimpleName(),
        failure == null ? null : failure.sqlState(),
        failure == null ? null : failure.constraint());
    return body(request, 500, "INTERNAL_ERROR", "Unexpected error", List.of());
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
