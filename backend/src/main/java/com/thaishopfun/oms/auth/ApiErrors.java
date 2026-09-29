package com.thaishopfun.oms.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Error body from section 4.8: {@code error}, {@code message}, {@code trace_id}. */
@Component
public class ApiErrors {

  private final JsonMapper jsonMapper;

  public ApiErrors(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  public void unauthorized(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException ignored)
      throws IOException {
    write(request, response, 401, "UNAUTHORIZED", TenantContextFilter.UNAUTHORIZED_MESSAGE);
  }

  public void forbidden(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException ignored)
      throws IOException {
    write(request, response, 403, "FORBIDDEN", "Access denied");
  }

  public void write(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String error,
      String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-store");
    if (status == 401) {
      response.setHeader("WWW-Authenticate", "Bearer");
    }
    String traceId = TraceIds.current(request);
    response.setHeader("X-Trace-Id", traceId);
    Map<String, String> body = new LinkedHashMap<>();
    body.put("error", error);
    body.put("message", message);
    body.put("trace_id", traceId);
    jsonMapper.writeValue(response.getOutputStream(), body);
  }
}
