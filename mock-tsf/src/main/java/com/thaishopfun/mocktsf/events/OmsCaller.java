package com.thaishopfun.mocktsf.events;

import com.thaishopfun.mocktsf.Hmacs;
import com.thaishopfun.mocktsf.MockProperties;
import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.mocktsf.idp.TokenIssuer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** HTTP calls this mock makes as TSF: HMAC events and the checkout reservation client. */
@Component
public class OmsCaller {

  public record CallResult(int status, String body, boolean reached) {}

  private final OmsEndpoint oms;
  private final MockProperties properties;
  private final TokenIssuer tokens;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public OmsCaller(OmsEndpoint oms, MockProperties properties, TokenIssuer tokens) {
    this.oms = oms;
    this.properties = properties;
    this.tokens = tokens;
  }

  public CallResult postEvent(byte[] raw, String eventId, String signature) {
    HttpRequest request =
        authorized(oms.events())
            .header("Content-Type", "application/json")
            .header("X-Event-Id", eventId)
            .header("X-Signature", signature)
            .POST(HttpRequest.BodyPublishers.ofByteArray(raw))
            .build();
    return send(request);
  }

  public CallResult postReservation(byte[] raw, String idempotencyKey) {
    HttpRequest request =
        authorized(oms.reservations())
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofByteArray(raw))
            .build();
    return send(request);
  }

  public CallResult deleteReservation(String reservationId) {
    HttpRequest request = authorized(oms.reservation(reservationId)).DELETE().build();
    return send(request);
  }

  public CallResult postDemoOrderCatalog() {
    HttpRequest request =
        HttpRequest.newBuilder(oms.demoOrderCatalog())
            .timeout(Duration.ofSeconds(8))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return send(request);
  }

  public String signInbox(long epochSeconds, byte[] raw) {
    String secret = properties.inboxSecrets().get(0);
    return Hmacs.header(secret, epochSeconds, raw);
  }

  private HttpRequest.Builder authorized(URI uri) {
    return HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(8))
        .header("Authorization", "Bearer " + tokens.tsfServiceToken());
  }

  private CallResult send(HttpRequest request) {
    try {
      HttpResponse<String> response =
          http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return new CallResult(
          response.statusCode(), response.body() == null ? "" : response.body(), true);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return new CallResult(0, "OMS unreachable", false);
    } catch (Exception ex) {
      return new CallResult(0, "OMS unreachable", false);
    }
  }

  static long now() {
    return Instant.now().getEpochSecond();
  }
}
