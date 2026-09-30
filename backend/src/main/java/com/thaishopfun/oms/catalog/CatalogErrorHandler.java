package com.thaishopfun.oms.catalog;

import com.thaishopfun.oms.auth.TraceIds;
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
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Error body from section 4.8 ({@code error}, {@code message}, {@code trace_id}) for the catalog
 * and warehouse controllers only. Import validation adds {@code errors}.
 */
@RestControllerAdvice(
    basePackages = {"com.thaishopfun.oms.catalog", "com.thaishopfun.oms.warehouse"})
class CatalogErrorHandler {

  private static final Logger log = LoggerFactory.getLogger(CatalogErrorHandler.class);

  @ExceptionHandler(CatalogApiException.class)
  ResponseEntity<Map<String, Object>> handle(CatalogApiException ex, HttpServletRequest request) {
    return body(request, ex.status(), ex.code(), ex.getMessage(), ex.details());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, Object>> unreadable(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    return body(request, 422, "VALIDATION_FAILED", "Request body is invalid", List.of());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<Map<String, Object>> mismatch(
      MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
    // Step 1: A malformed id in the path is simply a record that does not exist.
    if (ex.getParameter().hasParameterAnnotation(PathVariable.class)) {
      return body(request, 404, "NOT_FOUND", "Not found", List.of());
    }
    return body(request, 422, "VALIDATION_FAILED", ex.getName() + " is invalid", List.of());
  }

  @ExceptionHandler({
    MissingServletRequestParameterException.class,
    MissingServletRequestPartException.class
  })
  ResponseEntity<Map<String, Object>> missing(Exception ex, HttpServletRequest request) {
    return body(request, 422, "VALIDATION_FAILED", "A required parameter is missing", List.of());
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<Map<String, Object>> tooLarge(
      MaxUploadSizeExceededException ex, HttpServletRequest request) {
    return body(request, 413, "PAYLOAD_TOO_LARGE", "The file is too large", List.of());
  }

  @ExceptionHandler(MultipartException.class)
  ResponseEntity<Map<String, Object>> multipart(MultipartException ex, HttpServletRequest request) {
    return body(
        request, 422, "VALIDATION_FAILED", "A multipart file upload is required", List.of());
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
        "catalog request failed: {} sqlstate={}",
        ex.getClass().getSimpleName(),
        failure == null ? null : failure.sqlState());
    return body(request, 500, "INTERNAL_ERROR", "Unexpected error", List.of());
  }

  private static ResponseEntity<Map<String, Object>> body(
      HttpServletRequest request,
      int status,
      String code,
      String message,
      List<ImportRowError> details) {
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
