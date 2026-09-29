package com.thaishopfun.oms.auth;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

  private final MeService meService;

  public MeController(MeService meService) {
    this.meService = meService;
  }

  @GetMapping("/api/v1/me")
  public ResponseEntity<MeResponse> me() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(meService.current());
  }
}
