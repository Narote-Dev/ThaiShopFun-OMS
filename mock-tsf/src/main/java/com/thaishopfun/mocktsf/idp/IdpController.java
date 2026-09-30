package com.thaishopfun.mocktsf.idp;

import com.thaishopfun.mocktsf.ApiException;
import com.thaishopfun.mocktsf.MockProperties;
import com.thaishopfun.mocktsf.SeedData;
import com.thaishopfun.mocktsf.SigningKeys;
import com.thaishopfun.mocktsf.contract.ContractResponses;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/** OIDC discovery, JWKS, authorization code + PKCE, and client credentials. */
@RestController
@RequestMapping("/tsf-idp")
public class IdpController {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final long CODE_TTL_SECONDS = 120;

  private final MockProperties properties;
  private final SeedData shops;
  private final SigningKeys keys;
  private final TokenIssuer tokens;
  private final ContractResponses responses;
  private final ConcurrentHashMap<String, AuthCode> codes = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Refresh> refreshes = new ConcurrentHashMap<>();

  public IdpController(
      MockProperties properties,
      SeedData shops,
      SigningKeys keys,
      TokenIssuer tokens,
      ContractResponses responses) {
    this.properties = properties;
    this.shops = shops;
    this.keys = keys;
    this.tokens = tokens;
    this.responses = responses;
  }

  @GetMapping("/.well-known/openid-configuration")
  public ResponseEntity<String> discovery() {
    String issuer = properties.getIssuer();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("issuer", issuer);
    body.put("authorization_endpoint", issuer + "/authorize");
    body.put("token_endpoint", issuer + "/token");
    body.put("jwks_uri", issuer + "/.well-known/jwks.json");
    body.put("response_types_supported", java.util.List.of("code"));
    body.put("subject_types_supported", java.util.List.of("public"));
    body.put("id_token_signing_alg_values_supported", java.util.List.of("RS256"));
    body.put("code_challenge_methods_supported", java.util.List.of("S256"));
    body.put(
        "grant_types_supported",
        java.util.List.of("authorization_code", "refresh_token", "client_credentials"));
    body.put(
        "token_endpoint_auth_methods_supported", java.util.List.of("none", "client_secret_post"));
    return responses.outbound(200, "openid-configuration", body);
  }

  @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> jwks() {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(keys.jwksJson());
  }

  @GetMapping("/authorize")
  public ResponseEntity<?> authorize(
      @RequestParam(value = "response_type", required = false) String responseType,
      @RequestParam(value = "client_id", required = false) String clientId,
      @RequestParam(value = "redirect_uri", required = false) String redirectUri,
      @RequestParam(value = "code_challenge", required = false) String codeChallenge,
      @RequestParam(value = "code_challenge_method", required = false) String method,
      @RequestParam(value = "state", required = false) String state,
      @RequestParam(value = "scope", required = false) String scope,
      @RequestParam(value = "nonce", required = false) String nonce,
      @RequestParam(value = "login_hint", required = false) String loginHint) {
    // Step 1: Public client + PKCE only. Redirect targets stay on localhost.
    requireCodeRequest(responseType, clientId, redirectUri, codeChallenge, method);
    if (loginHint == null || loginHint.isBlank()) {
      return ResponseEntity.ok()
          .contentType(MediaType.TEXT_HTML)
          .body(picker(clientId, redirectUri, codeChallenge, method, state, scope, nonce));
    }
    SeedData.ShopUser user =
        shops
            .find(loginHint)
            .orElseThrow(() -> ApiException.badRequest("UNKNOWN_USER", "login_hint is unknown"));
    return redirectWithCode(user, clientId, redirectUri, codeChallenge, state, nonce);
  }

  @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  public ResponseEntity<String> token(@RequestParam MultiValueMap<String, String> form) {
    String grant = form.getFirst("grant_type");
    if ("authorization_code".equals(grant)) {
      return authorizationCode(form);
    }
    if ("refresh_token".equals(grant)) {
      return refresh(form);
    }
    if ("client_credentials".equals(grant)) {
      return clientCredentials(form);
    }
    throw ApiException.badRequest("UNSUPPORTED_GRANT", "grant_type is not supported");
  }

  private ResponseEntity<String> authorizationCode(MultiValueMap<String, String> form) {
    // Step 1: One-time code. The verifier must match the S256 challenge from /authorize.
    String code = form.getFirst("code");
    String verifier = form.getFirst("code_verifier");
    String redirect = form.getFirst("redirect_uri");
    String clientId = form.getFirst("client_id");
    AuthCode stored = code == null ? null : codes.remove(code);
    if (stored == null || stored.expiresAt().isBefore(Instant.now())) {
      throw ApiException.badRequest("INVALID_GRANT", "authorization code is invalid");
    }
    if (!stored.clientId().equals(clientId) || !stored.redirectUri().equals(redirect)) {
      throw ApiException.badRequest("INVALID_GRANT", "authorization code is invalid");
    }
    if (verifier == null || !s256(verifier).equals(stored.challenge())) {
      throw ApiException.badRequest("INVALID_GRANT", "PKCE verification failed");
    }
    SeedData.ShopUser user =
        shops
            .find(stored.userId())
            .orElseThrow(() -> ApiException.badRequest("INVALID_GRANT", "user is unknown"));
    return userToken(user, clientId, stored.nonce());
  }

  private ResponseEntity<String> refresh(MultiValueMap<String, String> form) {
    // Step 1: Rotate. The presented refresh token cannot be used again.
    String presented = form.getFirst("refresh_token");
    String clientId = form.getFirst("client_id");
    Refresh stored = presented == null ? null : refreshes.remove(presented);
    if (stored == null || !stored.clientId().equals(clientId)) {
      throw ApiException.badRequest("INVALID_GRANT", "refresh token is invalid");
    }
    SeedData.ShopUser user =
        shops
            .find(stored.userId())
            .orElseThrow(() -> ApiException.badRequest("INVALID_GRANT", "user is unknown"));
    return userToken(user, clientId, null);
  }

  private ResponseEntity<String> clientCredentials(MultiValueMap<String, String> form) {
    // Step 1: Two confidential clients. The audience follows who is calling whom.
    String clientId = form.getFirst("client_id");
    String secret = form.getFirst("client_secret");
    String audience;
    if (properties.getTsfClientId().equals(clientId)
        && properties.getTsfClientSecret().equals(secret)) {
      audience = TokenIssuer.OMS_INTERNAL_AUDIENCE;
    } else if (properties.getOmsServiceClientId().equals(clientId)
        && properties.getOmsServiceClientSecret().equals(secret)) {
      audience = TokenIssuer.TSF_INTERNAL_AUDIENCE;
    } else {
      throw ApiException.badRequest("INVALID_CLIENT", "client credentials were rejected");
    }
    Map<String, Object> body = tokenBody(tokens.serviceToken(clientId, audience), null, null);
    return responses.outbound(200, "token-response", body);
  }

  private ResponseEntity<String> userToken(SeedData.ShopUser user, String clientId, String nonce) {
    String refresh = randomToken();
    refreshes.put(refresh, new Refresh(user.userId(), clientId));
    return responses.outbound(
        200,
        "token-response",
        tokenBody(tokens.userAccessToken(user), refresh, tokens.idToken(user, clientId, nonce)));
  }

  private Map<String, Object> tokenBody(String accessToken, String refresh, String idToken) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("access_token", accessToken);
    body.put("token_type", "Bearer");
    body.put("expires_in", Math.toIntExact(properties.getAccessTokenSeconds()));
    if (refresh != null) {
      body.put("refresh_token", refresh);
    }
    if (idToken != null) {
      body.put("id_token", idToken);
    }
    return body;
  }

  private ResponseEntity<Void> redirectWithCode(
      SeedData.ShopUser user,
      String clientId,
      String redirectUri,
      String codeChallenge,
      String state,
      String nonce) {
    String code = randomToken();
    codes.put(
        code,
        new AuthCode(
            codeChallenge,
            redirectUri,
            clientId,
            user.userId(),
            nonce,
            Instant.now().plusSeconds(CODE_TTL_SECONDS)));
    UriComponentsBuilder location =
        UriComponentsBuilder.fromUriString(redirectUri).queryParam("code", code);
    if (state != null) {
      location.queryParam("state", state);
    }
    return ResponseEntity.status(HttpStatus.FOUND).location(location.build(true).toUri()).build();
  }

  private void requireCodeRequest(
      String responseType,
      String clientId,
      String redirectUri,
      String codeChallenge,
      String method) {
    if (!"code".equals(responseType)) {
      throw ApiException.badRequest("INVALID_REQUEST", "response_type must be code");
    }
    if (!properties.getPublicClientId().equals(clientId)) {
      throw ApiException.badRequest("INVALID_CLIENT", "client_id is not the OMS public client");
    }
    if (!"S256".equals(method) || codeChallenge == null || codeChallenge.isBlank()) {
      throw ApiException.badRequest("INVALID_REQUEST", "PKCE S256 is required");
    }
    if (!localhostRedirect(redirectUri)) {
      throw ApiException.badRequest("INVALID_REQUEST", "redirect_uri must be localhost");
    }
  }

  static boolean localhostRedirect(String redirectUri) {
    if (redirectUri == null || redirectUri.isBlank()) {
      return false;
    }
    URI uri;
    try {
      uri = URI.create(redirectUri);
    } catch (IllegalArgumentException ex) {
      return false;
    }
    if (uri.getFragment() != null || !uri.isAbsolute()) {
      return false;
    }
    String host = uri.getHost();
    return "http".equalsIgnoreCase(uri.getScheme())
        && ("localhost".equals(host) || "127.0.0.1".equals(host));
  }

  private String picker(
      String clientId,
      String redirectUri,
      String challenge,
      String method,
      String state,
      String scope,
      String nonce) {
    StringBuilder html = new StringBuilder();
    html.append(
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Mock TSF</title></head>");
    html.append("<body><h1>Mock ThaiShopFun</h1><p>Choose a shop.</p><ul>");
    for (SeedData.ShopUser user : shops.users()) {
      // Step 1: Encode every query value. build(true) treats the query as already encoded.
      UriComponentsBuilder link =
          UriComponentsBuilder.fromPath("/tsf-idp/authorize")
              .queryParam("response_type", query("code"))
              .queryParam("client_id", query(clientId))
              .queryParam("redirect_uri", query(redirectUri))
              .queryParam("code_challenge", query(challenge))
              .queryParam("code_challenge_method", query(method))
              .queryParam("login_hint", query(user.userId()));
      if (state != null) {
        link.queryParam("state", query(state));
      }
      if (scope != null) {
        link.queryParam("scope", query(scope));
      }
      if (nonce != null) {
        link.queryParam("nonce", query(nonce));
      }
      html.append("<li><a href=\"")
          .append(esc(link.build(true).toUriString()))
          .append("\">")
          .append(esc(user.shopName()))
          .append(" (")
          .append(esc(user.status()))
          .append(", ")
          .append(esc(user.shopId()))
          .append(")</a></li>");
    }
    html.append("</ul></body></html>");
    return html.toString();
  }

  private static String query(String value) {
    return UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8);
  }

  private static String esc(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  public static String s256(String verifier) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 is unavailable");
    }
  }

  private static String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private record AuthCode(
      String challenge,
      String redirectUri,
      String clientId,
      String userId,
      String nonce,
      Instant expiresAt) {}

  private record Refresh(String userId, String clientId) {}
}
