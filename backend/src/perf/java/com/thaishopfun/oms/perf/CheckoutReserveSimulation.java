package com.thaishopfun.oms.perf;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

/** Gatling load test for checkout reserve (NFR p95/p99). Run with {@code -Pperf gatling:test}. */
public class CheckoutReserveSimulation extends Simulation {

  private static final String BASE = System.getProperty("perf.baseUrl", "http://127.0.0.1:8080");
  private static final String TOKEN = System.getProperty("perf.token", "perf-token");
  private static final String SHOP = System.getProperty("perf.shopId", "perf-active");

  private static final List<Map<String, Object>> LISTING_FEED =
      IntStream.range(0, 200)
          .mapToObj(i -> Map.<String, Object>of("listingSku", "L-" + i))
          .toList();

  HttpProtocolBuilder httpProtocol =
      http
          .baseUrl(BASE)
          .acceptHeader("application/json")
          .contentTypeHeader("application/json")
          .authorizationHeader("Bearer " + TOKEN);

  ChainBuilder assignCheckout =
      exec(session -> session.set("checkoutId", "chk-perf-" + UUID.randomUUID()));

  ChainBuilder assignListing =
      exec(
          session ->
              session.set(
                  "listingSku",
                  "L-" + ThreadLocalRandom.current().nextInt(LISTING_FEED.size())));

  ChainBuilder reserve =
      assignCheckout
          .exec(assignListing)
          .exec(
              http("reserve-load")
                  .post("/internal/v1/inventory/reservations")
                  .header("Idempotency-Key", "#{checkoutId}")
                  .body(
                      StringBody(
                          """
                          {"checkout_id":"#{checkoutId}","tsf_shop_id":"%s","items":[{"listing_sku_id":"#{listingSku}","qty":1}]}
                          """
                              .formatted(SHOP)))
                  .check(
                      status().in(201, 409),
                      jsonPath("$.reservation_id").optional().saveAs("reservationId")));

  ChainBuilder release =
      doIf(session -> session.contains("reservationId"))
          .then(
              http("release-load")
                  .delete("/internal/v1/inventory/reservations/#{reservationId}")
                  .check(status().is(204)));

  PopulationBuilder warmUp =
      scenario("warm-up").exec(reserve).injectOpen(constantUsersPerSec(20).during(20));

  PopulationBuilder mixed =
      scenario("reserve-delete")
          .exec(reserve)
          .randomSwitch()
          .on(percent(70).then(pause(Duration.ZERO)), percent(30).then(release))
          .injectOpen(constantUsersPerSec(200).during(60));

  {
    setUp(warmUp, mixed)
        .protocols(httpProtocol)
        .assertions(
            global().failedRequests().count().is(0L),
            details("reserve-load").responseTime().percentile(95.0).lt(150),
            details("reserve-load").responseTime().percentile(99.0).lt(300));
  }
}
