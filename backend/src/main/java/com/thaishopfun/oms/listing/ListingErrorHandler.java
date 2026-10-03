package com.thaishopfun.oms.listing;

import com.thaishopfun.oms.auth.TraceIds;
import com.thaishopfun.oms.channel.exception.ChannelClientException;
import com.thaishopfun.oms.channel.exception.ChannelRateLimitedException;
import com.thaishopfun.oms.channel.exception.ChannelServerErrorException;
import com.thaishopfun.oms.channel.exception.ChannelUnavailableException;
import com.thaishopfun.oms.channel.exception.UnsupportedCapabilityException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.thaishopfun.oms.listing")
class ListingErrorHandler {

  private static final Logger log = LoggerFactory.getLogger(ListingErrorHandler.class);

  @ExceptionHandler(ListingApiException.class)
  ResponseEntity<Map<String, Object>> handle(ListingApiException ex, HttpServletRequest request) {
    return body(request, ex.status(), ex.code(), ex.getMessage());
  }

  @ExceptionHandler(UnsupportedCapabilityException.class)
  ResponseEntity<Map<String, Object>> capability(
      UnsupportedCapabilityException ex, HttpServletRequest request) {
    return body(request, 422, "CAPABILITY_UNSUPPORTED", ex.getMessage());
  }

  @ExceptionHandler(ChannelClientException.class)
  ResponseEntity<Map<String, Object>> channelClient(
      ChannelClientException ex, HttpServletRequest request) {
    return body(request, ex.statusCode(), ex.errorCode(), ex.getMessage());
  }

  @ExceptionHandler(ChannelRateLimitedException.class)
  ResponseEntity<Map<String, Object>> rateLimited(
      ChannelRateLimitedException ex, HttpServletRequest request) {
    return ResponseEntity.status(429)
        .header(
            "Retry-After",
            Long.toString(ex.retryAfterSeconds() == null ? 60 : ex.retryAfterSeconds()))
        .header("X-Trace-Id", TraceIds.current(request))
        .body(
            Map.of(
                "error",
                "RATE_LIMITED",
                "message",
                ex.getMessage(),
                "trace_id",
                TraceIds.current(request)));
  }

  @ExceptionHandler({ChannelServerErrorException.class, ChannelUnavailableException.class})
  ResponseEntity<Map<String, Object>> channelUnavailable(
      RuntimeException ex, HttpServletRequest request) {
    return body(request, 503, "CHANNEL_UNAVAILABLE", ex.getMessage());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> unexpected(Exception ex, HttpServletRequest request) {
    log.warn("listing request failed: {}", ex.getClass().getSimpleName());
    return body(request, 500, "INTERNAL_ERROR", "Unexpected error");
  }

  private static ResponseEntity<Map<String, Object>> body(
      HttpServletRequest request, int status, String code, String message) {
    String traceId = TraceIds.current(request);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", code);
    body.put("message", message);
    body.put("trace_id", traceId);
    return ResponseEntity.status(status)
        .header("X-Trace-Id", traceId)
        .header("Cache-Control", "no-store")
        .body(body);
  }
}
