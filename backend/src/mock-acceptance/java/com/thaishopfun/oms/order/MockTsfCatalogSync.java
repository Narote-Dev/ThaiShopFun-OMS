package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Keeps mock-tsf REST catalog aligned when tests POST inbox events directly to OMS. */
public final class MockTsfCatalogSync {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private MockTsfCatalogSync() {}

  public static void note(ObjectNode event) throws Exception {
    note(event, OrderIntakeMockRuntime.mockPort());
  }

  public static void note(ObjectNode event, int mockPort) throws Exception {
    ObjectNode body = JSON.createObjectNode();
    body.set("event", event);
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + mockPort + "/control/catalog/note-event"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .build();
    HttpResponse<String> response =
        HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(200);
  }
}
