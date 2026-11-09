package com.thaishopfun.oms.staging;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.thaishopfun.oms.auth.ApiErrors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("staging")
@RequestMapping("${oms.staging.idp-base-path:/staging-idp}")
class StagingIdpController {

  private final StagingProperties properties;
  private final StagingSigningKeys keys;
  private final StagingTokenIssuer tokens;
  private final StagingDemoAccountRegistry accounts;
  private final PasswordEncoder passwordEncoder;
  private final StagingLoginThrottler throttler;
  private final ApiErrors errors;

  StagingIdpController(
      StagingProperties properties,
      StagingSigningKeys keys,
      StagingTokenIssuer tokens,
      StagingDemoAccountRegistry accounts,
      PasswordEncoder passwordEncoder,
      StagingLoginThrottler throttler,
      ApiErrors errors) {
    this.properties = properties;
    this.keys = keys;
    this.tokens = tokens;
    this.accounts = accounts;
    this.passwordEncoder = passwordEncoder;
    this.throttler = throttler;
    this.errors = errors;
  }

  @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> jwks() {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(keys.jwksJson());
  }

  @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> login(
      @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    String loginId = body == null ? null : body.loginId();
    String clientIp = StagingClientIp.resolve(request);
    String throttleKey = StagingClientIp.throttleKey(clientIp, loginId);
    // Step 1: Rate limit and lockout per client address and username (no self-signup).
    StagingLoginThrottler.Result throttle = throttler.check(throttleKey);
    if (!throttle.permitted()) {
      errors.write(request, response, 429, "TOO_MANY_REQUESTS", throttle.message());
      return ResponseEntity.status(429).build();
    }
    if (body == null
        || loginId == null
        || loginId.isBlank()
        || body.password() == null
        || body.password().isBlank()) {
      errors.write(request, response, 400, "INVALID_REQUEST", "login_id and password are required");
      return ResponseEntity.badRequest().build();
    }
    var accountOpt = accounts.find(loginId);
    if (accountOpt.isEmpty()
        || !passwordEncoder.matches(body.password(), accountOpt.get().passwordHash())) {
      throttler.recordFailure(throttleKey);
      errors.write(request, response, 401, "UNAUTHORIZED", "ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง");
      return ResponseEntity.status(401).build();
    }
    // Step 2: Mint an access token with section 4.1 claims for the demo tenant.
    StagingDemoAccount account = accountOpt.get();
    throttler.recordSuccess(throttleKey);
    Instant membershipExpiry =
        "GRACE".equals(account.status())
            ? Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
            : Instant.now().plus(365, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    String accessToken = tokens.userAccessToken(account, membershipExpiry);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("access_token", accessToken);
    payload.put("token_type", "Bearer");
    payload.put("expires_in", properties.getAccessTokenSeconds());
    return ResponseEntity.ok(payload);
  }

  record LoginRequest(@JsonProperty("login_id") String loginId, String password) {}
}
