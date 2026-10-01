package com.thaishopfun.oms.channel.tsf;

import com.thaishopfun.oms.channel.TsfProperties;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class TsfTokenProvider {

  private static final Logger log = LoggerFactory.getLogger(TsfTokenProvider.class);
  private static final Duration REFRESH_SKEW = Duration.ofSeconds(60);
  private static final Duration TOKEN_HTTP_TIMEOUT = Duration.ofSeconds(10);

  private final TsfProperties properties;
  private final JsonMapper json;
  private final HttpClient client;
  private final ReentrantLock lock = new ReentrantLock();

  private volatile CachedToken cached;

  public TsfTokenProvider(TsfProperties properties, JsonMapper json) {
    this.properties = properties;
    this.json = json;
    this.client = HttpClient.newBuilder().connectTimeout(TOKEN_HTTP_TIMEOUT).build();
  }

  @PostConstruct
  void validateOnStartup() {
    // Step 1: Fail fast when TSF API is configured but the client secret is missing.
    if (properties.apiConfigured()
        && (properties.getClientSecret() == null || properties.getClientSecret().isBlank())) {
      throw new IllegalStateException(
          "oms.tsf.client-secret is required when oms.tsf.base-url is set");
    }
  }

  public String accessToken() {
    CachedToken current = cached;
    if (current != null && current.validAt(Instant.now())) {
      return current.token();
    }
    lock.lock();
    try {
      current = cached;
      if (current != null && current.validAt(Instant.now())) {
        return current.token();
      }
      cached = fetchToken();
      return cached.token();
    } finally {
      lock.unlock();
    }
  }

  /** Called once on HTTP 401 from TSF internal API. */
  public void invalidateAndRefresh() {
    lock.lock();
    try {
      cached = fetchToken();
    } finally {
      lock.unlock();
    }
  }

  private CachedToken fetchToken() {
    if (!properties.apiConfigured()) {
      throw new IllegalStateException("oms.tsf.base-url is not configured");
    }
    if (properties.getTokenUri() == null || properties.getTokenUri().isBlank()) {
      throw new IllegalStateException("oms.tsf.token-uri is not configured");
    }
    String form =
        "grant_type="
            + urlEncode("client_credentials")
            + "&client_id="
            + urlEncode(properties.getClientId())
            + "&client_secret="
            + urlEncode(properties.getClientSecret())
            + "&audience="
            + urlEncode(properties.getAudience());
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(properties.getTokenUri()))
            .timeout(TOKEN_HTTP_TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
    try {
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        log.warn("TSF token request failed status={}", response.statusCode());
        throw new IllegalStateException(
            "TSF token request failed with HTTP " + response.statusCode());
      }
      JsonNode body = json.readTree(response.body());
      JsonNode tokenNode = body.path("access_token");
      String token = tokenNode.isString() ? tokenNode.asString() : null;
      int expiresIn = body.path("expires_in").asInt(0);
      if (token == null || token.isBlank() || expiresIn < 1) {
        throw new IllegalStateException("TSF token response is invalid");
      }
      Instant expiresAt = Instant.now().plusSeconds(expiresIn);
      return new CachedToken(token, expiresAt);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("TSF token request interrupted", ex);
    } catch (java.io.IOException ex) {
      throw new IllegalStateException("TSF token request failed", ex);
    }
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private record CachedToken(String token, Instant expiresAt) {
    boolean validAt(Instant now) {
      return now.isBefore(expiresAt.minus(REFRESH_SKEW));
    }
  }
}
