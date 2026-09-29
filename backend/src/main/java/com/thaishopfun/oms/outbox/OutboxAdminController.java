package com.thaishopfun.oms.outbox;

import com.thaishopfun.oms.auth.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Admin retry path for DEAD outbox events. User JWT, tenant from the filter, OWNER or ADMIN. */
@RestController
class OutboxAdminController {

  private final OutboxAdminService admin;

  OutboxAdminController(OutboxAdminService admin) {
    this.admin = admin;
  }

  @GetMapping("/api/v1/outbox")
  Map<String, List<OutboxDeadEvent>> listDead(
      @RequestParam(name = "limit", defaultValue = "50") int limit,
      @RequestParam(name = "offset", defaultValue = "0") int offset) {
    return Map.of("events", admin.listDead(limit, offset));
  }

  @PostMapping("/api/v1/outbox/{id}/retry")
  OutboxRetryResponse retry(@PathVariable UUID id, HttpServletRequest request) {
    return admin.retry(id, request.getRemoteAddr());
  }
}

@RestControllerAdvice
class OutboxExceptionHandler {

  @ExceptionHandler(OutboxAccessException.class)
  ResponseEntity<Map<String, String>> handle(OutboxAccessException ex, HttpServletRequest request) {
    String traceId = TraceIds.current(request);
    Map<String, String> body = new LinkedHashMap<>();
    body.put("error", ex.code());
    body.put("message", ex.getMessage());
    body.put("trace_id", traceId);
    return ResponseEntity.status(ex.status())
        .header("X-Trace-Id", traceId)
        .header("Cache-Control", "no-store")
        .body(body);
  }
}
