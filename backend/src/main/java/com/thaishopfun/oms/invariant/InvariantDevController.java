package com.thaishopfun.oms.invariant;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local/e2e manual trigger (see docs/invariants.md). */
@RestController
@Profile({"local", "e2e", "test"})
@RequestMapping("/control/demo")
public class InvariantDevController {

  private final InvariantJob job;

  public InvariantDevController(InvariantJob job) {
    this.job = job;
  }

  @PostMapping("/invariants-check")
  public ResponseEntity<Map<String, Object>> runOnce() {
    int violations = job.runOnce();
    return ResponseEntity.ok(Map.of("status", "OK", "violations", violations));
  }
}
