package com.thaishopfun.mocktsf.contract;

import com.thaishopfun.mocktsf.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Serializes a body and refuses to send it when it does not match the contract schema. */
@Component
public class ContractResponses {

  public static final String TRACE_ATTRIBUTE = "trace_id";

  private static final Logger log = LoggerFactory.getLogger(ContractResponses.class);

  private final ContractValidator validator;
  private final JsonMapper json;

  public ContractResponses(JsonMapper json) {
    this.validator = ContractValidator.classpath();
    this.json = json;
  }

  public ContractValidator validator() {
    return validator;
  }

  public String write(Object body) {
    return json.writeValueAsString(body);
  }

  /** Inbound JSON. A mismatch is {@code 400 SCHEMA_VIOLATION}. */
  public void requireInboundEnvelope(String body) {
    validator.requireEnvelope(body);
  }

  public void requireInboundRest(String schemaName, String body) {
    validator.requireRest(schemaName, body);
  }

  /** Outbound JSON. A mismatch is our bug, not the caller's. */
  public ResponseEntity<String> outbound(int status, String schemaName, Object body) {
    String payload = write(body);
    try {
      validator.requireRest(schemaName, payload);
    } catch (ApiException ex) {
      log.error("outbound schema {} rejected the mock response", schemaName);
      throw new ApiException(500, "INTERNAL_ERROR", "Response did not match the contract");
    }
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(payload);
  }

  public ResponseEntity<String> error(
      HttpServletRequest request, int status, String code, String message) {
    Map<String, String> body = new LinkedHashMap<>();
    body.put("error", code);
    body.put("message", message);
    body.put("trace_id", traceId(request));
    return outbound(status, "error", body);
  }

  public static String traceId(HttpServletRequest request) {
    Object current = request == null ? null : request.getAttribute(TRACE_ATTRIBUTE);
    if (current instanceof String text && !text.isBlank()) {
      return text;
    }
    return java.util.UUID.randomUUID().toString().replace("-", "");
  }
}
