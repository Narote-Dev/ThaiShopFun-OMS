package com.thaishopfun.oms.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.MockTsfApplication;
import com.thaishopfun.mocktsf.contract.ContractValidator;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.channel.api.CancelRequest;
import com.thaishopfun.oms.channel.api.LabelContent;
import com.thaishopfun.oms.channel.api.ShipmentRequest;
import com.thaishopfun.oms.channel.tsf.TsfChannelAdapter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("test")
@SpringBootTest
class TsfChannelAdapterContractTest {

  private static final ContractValidator CONTRACT = ContractValidator.classpath();
  private static final String ISSUER = "http://mock-tsf-contract.test/tsf-idp";
  private static ConfigurableApplicationContext mock;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    startMock();
    int mockPort = mockPort();
    AuthTestSupport.registerDatabase(registry);
    registry.add("oms.inbox.worker-enabled", () -> "false");
    registry.add("oms.tsf.base-url", () -> "http://127.0.0.1:" + mockPort);
    registry.add("oms.tsf.token-uri", () -> "http://127.0.0.1:" + mockPort + "/tsf-idp/token");
    registry.add("oms.tsf.client-id", () -> "oms-service");
    registry.add("oms.tsf.client-secret", () -> "dev-oms-service-secret");
    registry.add("oms.tsf.audience", () -> "tsf-internal");
    registry.add("oms.channel.tsf.retry-max-attempts", () -> "3");
    registry.add("oms.channel.tsf.retry-wait-base", () -> "10ms");
    registry.add("oms.channel.tsf.retry-wait-max", () -> "20ms");
  }

  @AfterAll
  static void stopMock() {
    if (mock != null) {
      mock.close();
    }
  }

  @Autowired private TsfChannelAdapter adapter;
  @Autowired private JsonMapper json;
  @Autowired private ChannelResilienceTest.RecordingSleeper sleeper;

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Test
  void allSection47EndpointsMatchContractSchemas() throws Exception {
    ChannelAccountRef account =
        new ChannelAccountRef(UuidV7.generate(), UuidV7.generate(), "shop_active");

    // Step 1: Order pull endpoints.
    assertThat(
            CONTRACT.restErrors(
                "order-page",
                json.writeValueAsString(
                    adapter.listOrders(account, Instant.parse("2026-09-29T00:00:00Z"), null, 10))))
        .isEmpty();
    assertThat(
            CONTRACT.restErrors(
                "order", json.writeValueAsString(adapter.getOrder(account, "TSF-240929-000123"))))
        .isEmpty();
    assertThat(
            CONTRACT.restErrors(
                "payment-status",
                json.writeValueAsString(adapter.getPaymentStatus(account, "TSF-240929-000123"))))
        .isEmpty();
    assertThat(
            CONTRACT.restErrors(
                "listing-page", json.writeValueAsString(adapter.listListings(account, null))))
        .isEmpty();

    // Step 2: Fulfillment and cancel flows.
    var shipment =
        adapter.createShipment(
            account, "TSF-240929-000123", "contract-ship-key", new ShipmentRequest("FLASH"));
    assertThat(CONTRACT.restErrors("shipment", json.writeValueAsString(shipment))).isEmpty();
    LabelContent label = adapter.getLabel(account, shipment.shipmentId());
    assertThat(new String(label.bytes(), StandardCharsets.US_ASCII)).startsWith("%PDF");
    assertThat(
            CONTRACT.restErrors(
                "cancel-response",
                json.writeValueAsString(
                    adapter.requestCancel(
                        account,
                        "TSF-240929-000123",
                        "contract-cancel-key",
                        new CancelRequest("buyer")))))
        .isEmpty();
  }

  @Test
  void mockControlFaultsRetryAfterBeforeSuccess() throws Exception {
    String path = "/internal/v1/orders/TSF-240929-000124";
    HttpResponse<String> armed =
        HTTP.send(
            HttpRequest.newBuilder(mockUri("/control/faults"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {"method":"GET","path":"%s","status":429,"times":1,"retry_after":2}
                        """
                            .formatted(path)))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(armed.statusCode()).isEqualTo(200);
    sleeper.durations().clear();
    ChannelAccountRef account =
        new ChannelAccountRef(UuidV7.generate(), UuidV7.generate(), "shop_grace");

    // Step 1: Armed 429 fault is honored once; adapter waits at least Retry-After seconds.
    adapter.getOrder(account, "TSF-240929-000124");
    assertThat(sleeper.durations().stream().anyMatch(d -> d.compareTo(Duration.ofSeconds(2)) >= 0))
        .isTrue();
  }

  private static URI mockUri(String path) {
    return URI.create("http://127.0.0.1:" + mockPort() + path);
  }

  private static void startMock() {
    if (mock != null) {
      return;
    }
    SpringApplication app = MockTsfApplication.application();
    mock =
        app.run(
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--mock.issuer=" + ISSUER,
            "--spring.main.banner-mode=off",
            "--spring.main.register-shutdown-hook=false");
  }

  private static int mockPort() {
    String port = mock.getEnvironment().getProperty("local.server.port");
    if (port == null || port.isBlank()) {
      throw new IllegalStateException("mock-tsf did not bind a port");
    }
    return Integer.parseInt(port);
  }

  @TestConfiguration
  static class SleeperConfig {
    private final ChannelResilienceTest.RecordingSleeper sleeper =
        new ChannelResilienceTest.RecordingSleeper();

    @Bean
    @Primary
    Sleeper recordingSleeper() {
      return sleeper;
    }

    @Bean
    ChannelResilienceTest.RecordingSleeper recordingSleeperProbe() {
      return sleeper;
    }
  }
}
