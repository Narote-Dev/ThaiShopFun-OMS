package com.thaishopfun.oms.perf;

import io.gatling.javaapi.core.CoreDsl;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpDsl;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;

/** Gatling load test for checkout reserve (NFR p95/p99). Run with {@code -Pperf}. */
public class CheckoutReserveSimulation extends Simulation {

  private static final String BASE = System.getProperty("perf.baseUrl", "http://127.0.0.1:8080");

  HttpProtocolBuilder http =
      HttpDsl.http.baseUrl(BASE).acceptHeader("application/json").contentTypeHeader("application/json");

  ScenarioBuilder reserve =
      CoreDsl.scenario("reserve")
          .exec(
              HttpDsl.http("reserve")
                  .post("/internal/v1/inventory/reservations")
                  .header("Authorization", "Bearer perf-token")
                  .header("Idempotency-Key", "#{checkoutId}")
                  .body(
                      CoreDsl.StringBody(
                          """
                          {"checkout_id":"#{checkoutId}","tsf_shop_id":"perf-shop","items":[{"listing_sku_id":"L-1","qty":1}]}
                          """))
                  .check(HttpDsl.status().in(201, 409)));

  ScenarioBuilder release =
      CoreDsl.scenario("release")
          .exec(
              HttpDsl.http("release")
                  .delete("/internal/v1/inventory/reservations/#{reservationId}")
                  .header("Authorization", "Bearer perf-token")
                  .check(HttpDsl.status().is(204)));

  {
    setUp(
            reserve.injectOpen(
                CoreDsl.constantUsersPerSec(140).during(Duration.ofSeconds(20)),
                CoreDsl.constantUsersPerSec(140).during(Duration.ofSeconds(60))),
            release.injectOpen(
                CoreDsl.constantUsersPerSec(60).during(Duration.ofSeconds(20)),
                CoreDsl.constantUsersPerSec(60).during(Duration.ofSeconds(60))))
        .protocols(http)
        .assertions(
            CoreDsl.global().failedRequests().count().is(0L),
            CoreDsl.global().responseTime().percentile3().lt(300),
            CoreDsl.global().responseTime().percentile2().lt(150));
  }
}
