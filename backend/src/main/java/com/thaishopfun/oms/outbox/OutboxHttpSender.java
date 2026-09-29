package com.thaishopfun.oms.outbox;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** POSTs the stored envelope to TSF and signs {@code t + "." + rawBody} with HMAC-SHA256. */
@Component
public class OutboxHttpSender {

  public record SendResult(int status, Duration retryAfter) {

    boolean success() {
      return status >= 200 && status < 300;
    }

    boolean terminalClientError() {
      return status >= 400 && status < 500 && status != 408 && status != 429;
    }
  }

  private final OutboxProperties properties;
  private final JsonMapper json;
  private final HttpClient client;

  public OutboxHttpSender(OutboxProperties properties, JsonMapper json) {
    this.properties = properties;
    this.json = json;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(properties.getHttpTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public SendResult send(OutboxStore.Held held) throws IOException, InterruptedException {
    // Step 1: Rebuild one canonical body from jsonb so the signature matches the bytes we send.
    String raw = json.writeValueAsString(json.readTree(held.payload()));
    long timestamp = Instant.now().getEpochSecond();
    String signature = sign(properties.getWebhookSecret(), timestamp, raw);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(properties.getDestinationUrl()))
            .timeout(properties.getHttpTimeout())
            .header("Content-Type", "application/json")
            .header("X-Event-Id", held.id().toString())
            .header("X-Signature", "t=" + timestamp + ",v1=" + signature)
            .POST(HttpRequest.BodyPublishers.ofString(raw))
            .build();
    // Step 2: Drop the response body. It can echo shop data and must not land in a log.
    HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
    return new SendResult(response.statusCode(), retryAfter(response));
  }

  static String sign(String secret, long timestamp, String rawBody) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(
              secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest =
          mac.doFinal(
              (timestamp + "." + rawBody).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (Exception ex) {
      throw new IllegalStateException("HMAC-SHA256 is not available");
    }
  }

  private static Duration retryAfter(HttpResponse<?> response) {
    String header = response.headers().firstValue("Retry-After").orElse(null);
    if (header == null || header.isBlank()) {
      return null;
    }
    String value = header.trim();
    try {
      long seconds = Long.parseLong(value);
      return seconds > 0 ? Duration.ofSeconds(seconds) : null;
    } catch (NumberFormatException ignored) {
      try {
        ZonedDateTime when =
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US));
        Duration delay = Duration.between(Instant.now(), when.toInstant());
        return delay.isNegative() || delay.isZero() ? null : delay;
      } catch (DateTimeParseException ex) {
        return null;
      }
    }
  }
}
