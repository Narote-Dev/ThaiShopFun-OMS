package com.thaishopfun.oms.channel.tsf;

import com.thaishopfun.oms.channel.ChannelCallBudget;
import com.thaishopfun.oms.channel.ChannelProperties;
import com.thaishopfun.oms.channel.TsfProperties;
import com.thaishopfun.oms.channel.exception.ChannelClientException;
import com.thaishopfun.oms.channel.exception.ChannelIdempotencyConflictException;
import com.thaishopfun.oms.channel.exception.ChannelRateLimitedException;
import com.thaishopfun.oms.channel.exception.ChannelServerErrorException;
import com.thaishopfun.oms.channel.exception.ChannelUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
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

/**
 * TSF internal HTTP client. {@link HttpClient#connectTimeout} is capped by {@code min(10s,
 * oms.channel.tsf.http-timeout)}; the JDK does not support per-request connect timeouts. Each call
 * sets {@link HttpRequest#timeout} to {@code min(http-timeout, remaining budget)} for the full
 * connect+transfer bound.
 */
@Component
public class TsfHttpTransport {

  private final TsfProperties properties;
  private final TsfTokenProvider tokens;
  private final JsonMapper json;
  private final Clock clock;
  private final ChannelProperties channelProperties;
  private final HttpClient httpClient;

  public TsfHttpTransport(
      TsfProperties properties,
      TsfTokenProvider tokens,
      JsonMapper json,
      Clock clock,
      ChannelProperties channelProperties) {
    this.properties = properties;
    this.tokens = tokens;
    this.json = json;
    this.clock = clock;
    this.channelProperties = channelProperties;
    Duration connectTimeout =
        ChannelCallBudget.minDuration(
            Duration.ofSeconds(10), channelProperties.getTsf().getHttpTimeout());
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public HttpResult get(String path, Instant deadline) {
    return exchange("GET", path, deadline, null, Map.of());
  }

  public HttpResult post(String path, Instant deadline, String body, Map<String, String> headers) {
    return exchange("POST", path, deadline, body, headers);
  }

  private HttpResult exchange(
      String method, String path, Instant deadline, String body, Map<String, String> extraHeaders) {
    URI uri = URI.create(normalizeBase() + path);
    return sendOnce(method, uri, deadline, body, extraHeaders, false);
  }

  private HttpResult sendOnce(
      String method,
      URI uri,
      Instant deadline,
      String body,
      Map<String, String> extraHeaders,
      boolean retriedAuth) {
    String accessToken = tokens.accessToken(deadline);
    Duration httpTimeout =
        ChannelCallBudget.transportTimeout(
            clock, channelProperties.getTsf().getHttpTimeout(), deadline);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri)
            .timeout(httpTimeout)
            .header("Authorization", "Bearer " + accessToken);
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
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() == 401 && !retriedAuth) {
        tokens.invalidateAndRefresh(deadline);
        return sendOnce(method, uri, deadline, body, extraHeaders, true);
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
    if (status == 502 || status == 503 || status == 504) {
      throw new ChannelUnavailableException(errorMessage(body));
    }
    ParsedError parsed = parseError(body);
    if (status >= 500) {
      throw new ChannelServerErrorException(
          status,
          parsed.error(),
          parsed.message() != null ? parsed.message() : errorMessage(body),
          parsed.traceId());
    }
    throw new ChannelClientException(
        status,
        parsed.error(),
        parsed.message() != null ? parsed.message() : errorMessage(body),
        parsed.traceId());
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
      return new ParsedError(null, null, null);
    }
    try {
      JsonNode node = json.readTree(body);
      JsonNode errorNode = node.path("error");
      JsonNode messageNode = node.path("message");
      JsonNode traceNode = node.path("trace_id");
      return new ParsedError(
          errorNode.isString() ? errorNode.asString() : null,
          messageNode.isString() ? messageNode.asString() : null,
          traceNode.isString() ? traceNode.asString() : null);
    } catch (Exception ex) {
      return new ParsedError(null, null, null);
    }
  }

  private Integer retryAfterSeconds(HttpResponse<?> response) {
    String header = response.headers().firstValue("Retry-After").orElse(null);
    if (header == null || header.isBlank()) {
      return null;
    }
    String value = header.trim();
    try {
      long seconds = Long.parseLong(value);
      if (seconds < 0) {
        return null;
      }
      return (int) Math.min(seconds, Integer.MAX_VALUE);
    } catch (NumberFormatException ignored) {
      try {
        ZonedDateTime when =
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US));
        Duration delay = Duration.between(clock.instant(), when.toInstant());
        if (delay.isNegative() || delay.isZero()) {
          return 0;
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

  private record ParsedError(String error, String message, String traceId) {}
}
