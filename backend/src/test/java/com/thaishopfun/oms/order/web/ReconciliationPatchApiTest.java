package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.catalog.CatalogHttp;
import com.thaishopfun.oms.invariant.SkipInvariantCheck;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** PATCH issue status must run in tenant tx (FORCE RLS); cross-tenant id returns 404. */
@SkipInvariantCheck("Seeds late ship_by for reconciliation HTTP PATCH")
class ReconciliationPatchApiTest extends OrderIntegrationTest {

  @Test
  void patchOpenAckResolved_andCrossTenantNotFound() throws Exception {
    CatalogHttp.Shop shopA = http.catalog().shop();
    OrderFixture.Shop ctxA = OrderFixture.shopFor(shopA);
    CatalogHttp.Shop shopB = http.catalog().shop();
    OrderFixture.shopFor(shopB);

    UUID orderId = UuidV7.generate();
    UUID runId = UuidV7.generate();
    UUID issueId = UuidV7.generate();
    Instant pastShipBy = Instant.now().minus(2, ChronoUnit.DAYS);
    try (var admin = AuthTestSupport.admin();
        var order =
            admin.prepareStatement(
                """
                INSERT INTO sales_order (
                  id, tenant_id, channel_account_id, external_order_id, order_status, payment_status,
                  fulfillment_status, hold_reason, payment_method, currency, subtotal, shipping_fee,
                  discount, grand_total, ordered_at, updated_at, ship_by, version
                ) VALUES (?, ?, ?, 'API-LATE', 'ACTIVE', 'PAID', 'PACKED', 'NONE', 'PREPAID', 'THB',
                  100, 0, 0, 100, now(), now(), ?, 0)
                """);
        var run =
            admin.prepareStatement(
                """
                INSERT INTO reconciliation_run (id, tenant_id, trigger, started_at, finished_at, issues_found)
                VALUES (?, ?, 'MANUAL', now(), now(), 1)
                """);
        var issue =
            admin.prepareStatement(
                """
                INSERT INTO reconciliation_issue (id, tenant_id, run_id, rule, order_id, details, status)
                VALUES (?, ?, ?, 'SHIP_BY_LATE', ?, '{}'::jsonb, 'OPEN')
                """)) {
      order.setObject(1, orderId);
      order.setObject(2, ctxA.tenantId());
      order.setObject(3, ctxA.channelAccountId());
      order.setTimestamp(4, Timestamp.from(pastShipBy));
      order.executeUpdate();

      run.setObject(1, runId);
      run.setObject(2, ctxA.tenantId());
      run.executeUpdate();

      issue.setObject(1, issueId);
      issue.setObject(2, ctxA.tenantId());
      issue.setObject(3, runId);
      issue.setObject(4, orderId);
      issue.executeUpdate();
    }

    CatalogHttp.Result list =
        http.get("/api/v1/reconciliation/issues?status=OPEN&rule=SHIP_BY_LATE", shopA.owner());
    assertThat(list.status()).isEqualTo(200);
    JsonNode items = list.body().path("items");
    assertThat(items.isArray()).isTrue();
    assertThat(items.size()).isGreaterThanOrEqualTo(1);
    String listedId = items.get(0).path("id").asString();
    assertThat(listedId).isEqualTo(issueId.toString());

    assertThat(
            http.patch(
                    "/api/v1/reconciliation/issues/" + issueId,
                    shopA.owner(),
                    Map.of("status", "ACK"))
                .status())
        .isEqualTo(200);
    assertThat(
            http.get("/api/v1/reconciliation/issues?status=ACK&rule=SHIP_BY_LATE", shopA.owner())
                .body()
                .path("items")
                .get(0)
                .path("status")
                .asString())
        .isEqualTo("ACK");

    assertThat(
            http.patch(
                    "/api/v1/reconciliation/issues/" + issueId,
                    shopA.owner(),
                    Map.of("status", "RESOLVED"))
                .status())
        .isEqualTo(200);
    assertThat(
            http.get(
                    "/api/v1/reconciliation/issues?status=RESOLVED&rule=SHIP_BY_LATE",
                    shopA.owner())
                .body()
                .path("items")
                .get(0)
                .path("status")
                .asString())
        .isEqualTo("RESOLVED");

    assertThat(
            http.patch(
                    "/api/v1/reconciliation/issues/" + listedId,
                    shopB.owner(),
                    Map.of("status", "ACK"))
                .status())
        .isEqualTo(404);
  }
}
