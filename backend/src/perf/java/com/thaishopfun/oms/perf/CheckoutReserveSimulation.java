package com.thaishopfun.oms.perf;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.CoreDsl;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpDsl;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** Gatling load test for checkout reserve (NFR p95/p99). Run with {@code -Pperf gatling:test}. */
public class CheckoutReserveSimulation extends Simulation {

  private static final String BASE = System.getProperty("perf.baseUrl", "http://127.0.0.1:8080");
  private static final String TOKEN = System.getProperty("perf.token", "perf-token");
  private static final String SHOP = System.getProperty("perf.shopId", "perf-active");

  private static final FeederBuilder<Object> CHECKOUT_IDS =
      CoreDsl.feeder(
          new Iterator<Map<String, Object>>() {
            @Override
            public boolean hasNext() {
              return true;
            }

            @Override
            public Map<String, Object> next() {
              return Map.of("checkoutId", "chk-perf-" + UUID.randomUUID());
            }
          });

  private static final FeederBuilder<Object> LISTINGS =
      CoreDsl.feeder(
          Stream.iterate(0, i -> i + 1)
              .limit(200)
              .map(i -> Map.<String, Object>of("listingSku", "L-" + i))
              .iterator());

  HttpProtocolBuilder http =
      HttpDsl.http
          .baseUrl(BASE)
          .acceptHeader("application/json")
          .contentTypeHeader("application/json")
          .authorizationHeader("Bearer " + TOKEN);

  ChainBuilder reserve =
      CoreDsl
          .feed(CHECKOUT_IDS)
          .feed(LISTINGS)
          .exec(
              HttpDsl.http("reserve-load")
                  .post("/internal/v1/inventory/reservations")
                  .header("Idempotency-Key", "#{checkoutId}")
                  .body(
                      CoreDsl.StringBody(
                          """
                          {"checkout_id":"#{checkoutId}","tsf_shop_id":"%s","items":[{"listing_sku_id":"#{listingSku}","qty":1}]}
                          """
                              .formatted(SHOP)))
                  .check(
                      HttpDsl.status().in(201, 409),
                      HttpDsl.jsonPath("$.reservation_id")
                          .optional()
                          .saveAs("reservationId")));

  ChainBuilder release =
      CoreDsl.doIf(session -> session.contains("reservationId"))
          .then(
              HttpDsl.http("release-load")
                  .delete("/internal/v1/inventory/reservations/#{reservationId}")
                  .check(HttpDsl.status().is(204)));

  ScenarioBuilder warmUp =
      CoreDsl.scenario("warm-up").exec(reserve).injectOpen(CoreDsl.constantUsersPerSec(20).during(20));

  ScenarioBuilder mixed =
      CoreDsl
          .scenario("reserve-delete")
          .exec(reserve)
          .randomSwitch()
          .on(
              CoreDsl.percent(70).then(CoreDsl.pause(Duration.ZERO)),
              CoreDsl.percent(30).then(release))
          .injectOpen(CoreDsl.constantUsersPerSec(200).during(60));

  {
    setUp(warmUp, mixed)
        .protocols(http)
        .assertions(
            CoreDsl.global().failedRequests().count().is(0L),
            CoreDsl.details("reserve-load").responseTime().percentile(95.0).lt(150),
            CoreDsl.details("reserve-load").responseTime().percentile(99.0).lt(300));
  }
}
