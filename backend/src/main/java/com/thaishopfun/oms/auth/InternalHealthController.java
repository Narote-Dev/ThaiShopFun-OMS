package com.thaishopfun.oms.auth;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /internal/v1/health}. Client-credentials only. No tenant context. */
@RestController
public class InternalHealthController {

  @GetMapping("/internal/v1/health")
  public Map<String, String> health() {
    return Map.of("status", "UP");
  }
}
