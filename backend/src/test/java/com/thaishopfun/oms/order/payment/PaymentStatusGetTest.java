package com.thaishopfun.oms.order.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.thaishopfun.oms.channel.*;
import com.thaishopfun.oms.channel.tsf.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PaymentStatusGetTest {
  @Test
  void fallbackGetReadsPaymentAndRefundThroughTsfAdapter() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var hits = new AtomicInteger();
    server.createContext(
        "/token",
        exchange -> {
          byte[] body =
              "{\"access_token\":\"test-token\",\"expires_in\":3600}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/internal/v1/orders/TSF-1/payment-status",
        exchange -> {
          assertThat(exchange.getRequestMethod()).isEqualTo("GET");
          hits.incrementAndGet();
          byte[] body =
              """
          {"order_id":"TSF-1","status":"PAID","refunds":[
          {"refund_id":"r1","status":"SUCCEEDED","amount":295,"currency":"THB"}]}
          """
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    var meters = new SimpleMeterRegistry();
    try {
      var clock = Clock.systemUTC();
      var properties = new ChannelProperties();
      var tsf = new TsfProperties();
      String origin = "http://127.0.0.1:" + server.getAddress().getPort();
      tsf.setBaseUrl(origin);
      tsf.setTokenUri(origin + "/token");
      tsf.setClientId("oms");
      tsf.setClientSecret("test-secret");
      tsf.setAudience("tsf-internal");
      var json = new JsonMapper();
      var metrics = new ChannelMetrics(meters);
      var adapter =
          new TsfChannelAdapter(
              new AccountResilienceRegistry(properties, metrics),
              properties,
              metrics,
              duration -> {},
              clock,
              new TsfHttpTransport(
                  tsf, new TsfTokenProvider(tsf, properties, json, clock), json, clock, properties),
              json);
      var payment =
          adapter.getPaymentStatus(
              new ChannelAccountRef(UUID.randomUUID(), UUID.randomUUID(), "shop"), "TSF-1");
      assertThat(hits).hasValue(1);
      assertThat(payment.status()).isEqualTo("PAID");
      assertThat(payment.refunds())
          .singleElement()
          .satisfies(
              refund -> {
                assertThat(refund.status()).isEqualTo("SUCCEEDED");
                assertThat(refund.amount()).isEqualByComparingTo("295");
              });
    } finally {
      meters.close();
      server.stop(0);
    }
  }
}
