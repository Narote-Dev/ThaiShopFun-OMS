package com.thaishopfun.oms.channel.tsf;

import com.thaishopfun.oms.channel.TsfProperties;
import com.thaishopfun.oms.channel.exception.ChannelClientException;
import com.thaishopfun.oms.channel.exception.ChannelIdempotencyConflictException;
import com.thaishopfun.oms.channel.exception.ChannelRateLimitedException;
import com.thaishopfun.oms.channel.exception.ChannelUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class TsfHttpTransport {

  private final TsfProperties properties;
  private final TsfTokenProvider tokens;
  private final JsonMapper json;
  private final HttpClient client;

  public TsfHttpTransport(TsfProperties properties, TsfTokenProvider tokens, JsonMapper json) {
    this.properties = properties;
    this.tokens = tokens;
    this.json = json;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public HttpResult get(String path, Duration timeout) {
    return exchange("GET", path, timeout, null, Map.of());
  }

  public HttpResult post(String path, Duration timeout, String body, Map<String, String> headers) {
    return exchange("POST", path, timeout, body, headers);
  }

  private HttpResult exchange(
      String method, String path, Duration timeout, String body, Map<String, String> extraHeaders) {
    URI uri = URI.create(normalizeBase() + path);
    return sendOnce(method, uri, timeout, body, extraHeaders, false);
  }

  private HttpResult sendOnce(
      String method,
      URI uri,
      Duration timeout,
      String body,
      Map<String, String> extraHeaders,
      boolean retriedAuth) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Authorization", "Bearer " + tokens.accessToken());
    for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
      builder.header(header.getKey(), header.getValue());
    }
    if ("POST".equals(method)) {
      builder.header("Content-Type", "application/json");
      builder.POST(
          HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
    } else {
      builder.GET();
    }
    try {
      HttpResponse<byte[]> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() == 401 && !retriedAuth) {
        tokens.invalidateAndRefresh();
        return sendOnce(method, uri, timeout, body, extraHeaders, true);
      }
      return mapResponse(response);
    } catch (IOException ex) {
      throw new ChannelUnavailableException("TSF HTTP call failed", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ChannelUnavailableException("TSF HTTP call interrupted", ex);
    }
  }

  private HttpResult mapResponse(HttpResponse<byte[]> response) {
    int status = response.statusCode();
    byte[] body = response.body() == null ? new byte[0] : response.body();
    if (status >= 200 && status < 300) {
      return new HttpResult(status, body);
    }
    if (status == 429) {
      throw new ChannelRateLimitedException(errorMessage(body), retryAfterSeconds(response));
    }
    if (status == 409) {
      throw new ChannelIdempotencyConflictException(errorMessage(body));
    }
    if (status >= 500) {
      throw new ChannelUnavailableException(errorMessage(body));
    }
    ParsedError parsed = parseError(body);
    throw new ChannelClientException(status, parsed.error(), parsed.message());
  }

  private String errorMessage(byte[] body) {
    ParsedError parsed = parseError(body);
    if (parsed.message() != null && !parsed.message().isBlank()) {
      return parsed.message();
    }
    return parsed.error() != null ? parsed.error() : "TSF request failed";
  }

  private ParsedError parseError(byte[] body) {
    if (body.length == 0) {
      return new ParsedError(null, null);
    }
    try {
      JsonNode node = json.readTree(body);
      JsonNode errorNode = node.path("error");
      JsonNode messageNode = node.path("message");
      return new ParsedError(
          errorNode.isString() ? errorNode.asString() : null,
          messageNode.isString() ? messageNode.asString() : null);
    } catch (Exception ex) {
      return new ParsedError(null, null);
    }
  }

  private static Integer retryAfterSeconds(HttpResponse<?> response) {
    String header = response.headers().firstValue("Retry-After").orElse(null);
    if (header == null || header.isBlank()) {
      return null;
    }
    String value = header.trim();
    try {
      long seconds = Long.parseLong(value);
      return seconds > 0 ? (int) Math.min(seconds, Integer.MAX_VALUE) : null;
    } catch (NumberFormatException ignored) {
      try {
        ZonedDateTime when =
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US));
        Duration delay = Duration.between(Instant.now(), when.toInstant());
        if (delay.isZero() || delay.isNegative()) {
          return null;
        }
        return (int) Math.min(delay.toSeconds(), Integer.MAX_VALUE);
      } catch (DateTimeParseException ex) {
        return null;
      }
    }
  }

  private String normalizeBase() {
    String base = properties.getBaseUrl().trim();
    if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    if (!base.endsWith("/internal/v1")) {
      base = base + "/internal/v1";
    }
    return base;
  }

  public record HttpResult(int statusCode, byte[] body) {}

  private record ParsedError(String error, String message) {}
}
