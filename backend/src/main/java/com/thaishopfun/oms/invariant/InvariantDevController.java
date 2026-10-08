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
    InvariantJobResult result = job.runOnce();
    String status = result.healthy() ? "OK" : result.checkFailed() > 0 ? "FAILED" : "VIOLATIONS";
    var body =
        Map.<String, Object>of(
            "status",
            status,
            "violations",
            result.violations(),
            "checkFailed",
            result.checkFailed());
    if (result.checkFailed() > 0) {
      return ResponseEntity.status(503).body(body);
    }
    return ResponseEntity.ok(body);
  }
}
