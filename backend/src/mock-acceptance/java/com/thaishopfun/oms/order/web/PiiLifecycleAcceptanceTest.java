package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.inbox.InboxProperties;
import com.thaishopfun.oms.inbox.InboxWorker;
import com.thaishopfun.oms.invariant.VerifyInvariants;
import com.thaishopfun.oms.order.*;
import com.thaishopfun.oms.order.intake.OrderCreatedHandler;
import com.thaishopfun.oms.order.intake.OrderUpdatedHandler;
import com.thaishopfun.oms.pii.*;
import com.thaishopfun.oms.pii.lifecycle.OrderPiiLifecycle;
import com.thaishopfun.oms.pii.lifecycle.PiiLifecycleJob;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@org.springframework.test.context.TestPropertySource(
    properties = {"oms.pii.redaction.batch-size=2", "oms.inbox.hmac-secrets=t27-inbox-test-only"})
@VerifyInvariants
class PiiLifecycleAcceptanceTest extends OrderIntegrationTest {
  @Autowired SalesOrderRepository orders;
  @Autowired OrderRecipientRepository recipients;
  @Autowired OrderStatusHistoryRepository history;
  @Autowired PlatformTransactionManager transactions;
  @Autowired JdbcTemplate jdbc;
  @Autowired PiiLifecycleJob job;
  @Autowired OrderPiiLifecycle lifecycle;
  @Autowired InboxProperties inboxProperties;
  @Autowired InboxWorker worker;
  @MockitoSpyBean OrderCreatedHandler createdHandler;
  @MockitoSpyBean OrderUpdatedHandler updatedHandler;
  private OrderFixture fixture;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @BeforeEach
  void fixture() {
    fixture = new OrderFixture(orders, recipients, history, transactions);
  }

  @Test
  void dueCancelledRecipientRedactsOnceWithCountOnlyAuditPhoneSearchAndApiDetail()
      throws Exception {
    var owner = http.catalog().shop();
    var shop = OrderFixture.shopFor(owner);
    var due = cancelled(shop, "due");
    var future = cancelled(shop, "future");
    fixture.inTenant(
        shop.tenantId(),
        () -> {
          lifecycle.onOrderClosed(due.id(), Instant.now().minus(91, ChronoUnit.DAYS));
          lifecycle.onOrderClosed(future.id(), Instant.now());
          assertThat(recipients.findOrderIdsByPhone(OrderFixture.PHONE)).contains(due.id());
          return null;
        });
    job.runOnce();
    fixture.inTenant(
        shop.tenantId(),
        () -> {
          var r = recipients.find(due.id()).orElseThrow();
          assertThat(r.piiStatus()).isEqualTo("REDACTED");
          assertThat(r.name()).isNull();
          assertThat(r.phone()).isNull();
          assertThat(r.address()).isNull();
          assertThat(r.phoneLast4()).isNull();
          assertThat(r.province()).isEqualTo("Bangkok");
          assertThat(r.postcode()).isEqualTo("10110");
          assertThat(recipients.find(future.id()).orElseThrow().piiStatus()).isEqualTo("ACTIVE");
          assertThat(recipients.findOrderIdsByPhone(OrderFixture.PHONE))
              .containsExactly(future.id());
          assertThat(
                  jdbc.queryForObject(
                      "SELECT phone_hash FROM order_recipient WHERE order_id = ?",
                      byte[].class,
                      due.id()))
              .isNull();
          var audit =
              jdbc.queryForMap(
                  "SELECT actor_type, \"after\"::text AS detail FROM audit_log WHERE action = 'PII_REDACTED'");
          assertThat(audit.get("actor_type")).isEqualTo("SYSTEM");
          assertThat(JSON.readTree(audit.get("detail").toString()))
              .isEqualTo(JSON.readTree("{\"count\":1}"));
          return null;
        });
    var detail = http.get("/api/v1/orders/" + due.id(), owner.owner());
    assertThat(detail.status()).isEqualTo(200);
    assertThat(detail.body().path("recipient").path("pii_status").asString()).isEqualTo("REDACTED");
    assertThat(detail.raw())
        .doesNotContain(OrderFixture.NAME, OrderFixture.PHONE, OrderFixture.ADDRESS);
    var search = http.get("/api/v1/orders?q=0812345678", owner.owner());
    assertThat(search.status()).isEqualTo(200);
    assertThat(search.raw()).doesNotContain(due.id().toString()).contains(future.id().toString());
    job.runOnce();
    fixture.inTenant(
        shop.tenantId(),
        () -> {
          assertThat(
                  jdbc.queryForObject(
                      "SELECT count(*) FROM audit_log WHERE action = 'PII_REDACTED'",
                      Integer.class))
              .isEqualTo(1);
          return null;
        });
  }

  @Test
  void eachRecipientBatchHasOneCountOnlyAuditRow() throws Exception {
    var shop = OrderFixture.shopFor(http.catalog().shop());
    for (int i = 0; i < 5; i++) {
      var order = cancelled(shop, "batch");
      fixture.inTenant(
          shop.tenantId(),
          () -> {
            lifecycle.onOrderClosed(order.id(), Instant.now().minus(91, ChronoUnit.DAYS));
            return null;
          });
    }
    job.runOnce();
    fixture.inTenant(
        shop.tenantId(),
        () -> {
          assertThat(
                  jdbc.queryForList(
                      "SELECT (\"after\"->>'count')::int FROM audit_log WHERE action = 'PII_REDACTED'",
                      Integer.class))
              .containsExactlyInAnyOrder(2, 2, 1);
          return null;
        });
  }

  @Test
  void suspendedExpiredTenantStillRedacts() throws Exception {
    var owner = http.catalog().shop();
    var shop = OrderFixture.shopFor(owner);
    var order = cancelled(shop, "suspended");
    fixture.inTenant(
        shop.tenantId(),
        () -> {
          lifecycle.onOrderClosed(order.id(), Instant.now().minus(91, ChronoUnit.DAYS));
          return null;
        });
    try (var admin = AuthTestSupport.admin();
        var ps =
            admin.prepareStatement(
                "UPDATE tenant SET entitlement_status = 'SUSPENDED', entitlement_expires_at = now() - interval '1 day' WHERE id = ?")) {
      ps.setObject(1, shop.tenantId());
      ps.executeUpdate();
    }
    job.runOnce();
    fixture.inTenant(
        shop.tenantId(),
        () -> {
          assertThat(recipients.find(order.id()).orElseThrow().piiStatus()).isEqualTo("REDACTED");
          return null;
        });
  }

  @Test
  void rotationKeepsOldRowsReadableNewRowsUseK2AndPhoneHashSearchSurvives() throws Exception {
    var shop = OrderFixture.shopFor(http.catalog().shop());
    var old = cancelled(shop, "old");
    var fresh = cancelled(shop, "fresh");
    var props = new PiiProperties();
    props.setKeys(
        "k1:"
            + b64("test-only-pii-key-1-not-a-secret")
            + ",k2:"
            + b64("test-only-pii-key-2-not-a-secret"));
    props.setHashKey(b64("test-only-pii-hmac-not-a-secret!"));
    props.setActiveKeyId("k1");
    var before = new OrderRecipientRepository(jdbc, new PiiCipher(PiiKeyRing.parse(props)));
    fixture.inTenant(
        shop.tenantId(),
        () -> {
          before.update(
              old.id(),
              new Recipient(
                  OrderFixture.NAME, OrderFixture.PHONE, OrderFixture.ADDRESS, "Bangkok", "10110"));
          return null;
        });
    props.setActiveKeyId("k2");
    var after = new OrderRecipientRepository(jdbc, new PiiCipher(PiiKeyRing.parse(props)));
    fixture.inTenant(
        shop.tenantId(),
        () -> {
          assertThat(after.find(old.id()).orElseThrow().name()).isEqualTo(OrderFixture.NAME);
          assertThat(after.find(old.id()).orElseThrow().phone()).isEqualTo(OrderFixture.PHONE);
          after.update(
              fresh.id(),
              new Recipient(
                  OrderFixture.NAME, OrderFixture.PHONE, OrderFixture.ADDRESS, "Bangkok", "10110"));
          assertThat(after.find(fresh.id()).orElseThrow().address())
              .isEqualTo(OrderFixture.ADDRESS);
          assertThat(after.findOrderIdsByPhone("+66812345678"))
              .containsExactlyInAnyOrder(old.id(), fresh.id());
          for (var entry : Map.of(old.id(), "k1", fresh.id(), "k2").entrySet()) {
            byte[] sealed =
                jdbc.queryForObject(
                    "SELECT phone_enc FROM order_recipient WHERE order_id = ?",
                    byte[].class,
                    entry.getKey());
            assertThat(new String(sealed, 2, sealed[1], StandardCharsets.US_ASCII))
                .isEqualTo(entry.getValue());
          }
          return null;
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"order.created", "order.updated"})
  void terminalPayloadScrubPreservesHashAndDuplicatePostDoesNotRunHandler(String eventType)
      throws Exception {
    var owner = http.catalog().shop();
    var shop = OrderFixture.shopFor(owner);
    String eventId = UUID.randomUUID().toString();
    String payload =
        JSON.writeValueAsString(
            Map.of(
                "event_id",
                eventId,
                "event_type",
                eventType,
                "tsf_shop_id",
                shop.tsfShopId(),
                "aggregate_id",
                "pii-test",
                "aggregate_version",
                1,
                "data",
                Map.of(
                    "order_id",
                    "pii-test",
                    "recipient",
                    Map.of(
                        "name",
                        OrderFixture.NAME,
                        "phone",
                        "0812341234",
                        "address",
                        Map.of("line1", "99/1 ถ.สุขุมวิท"),
                        "province",
                        "Bangkok",
                        "postcode",
                        "10110"),
                    "lines",
                    List.of(Map.of("sku", "keep", "qty", 2)),
                    "grand_total",
                    100)));
    byte[] hash =
        MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
    UUID oldInbox = seedEvent(shop, "inbox_event", "PROCESSED", 8, eventId, payload, hash);
    UUID youngInbox =
        seedEvent(shop, "inbox_event", "PROCESSED", 6, UUID.randomUUID().toString(), payload, hash);
    UUID deadInbox =
        seedEvent(shop, "inbox_event", "DEAD", 8, UUID.randomUUID().toString(), payload, hash);
    UUID failedInbox =
        seedEvent(shop, "inbox_event", "FAILED", 8, UUID.randomUUID().toString(), payload, hash);
    UUID receivedInbox =
        seedEvent(shop, "inbox_event", "RECEIVED", 8, UUID.randomUUID().toString(), payload, hash);
    UUID sent = seedEvent(shop, "outbox_event", "SENT", 8, null, payload, hash);
    UUID youngSent = seedEvent(shop, "outbox_event", "SENT", 6, null, payload, hash);
    UUID dead = seedEvent(shop, "outbox_event", "DEAD", 8, null, payload, hash);
    UUID pending = seedEvent(shop, "outbox_event", "PENDING", 8, null, payload, hash);
    job.runOnce();
    fixture.inTenant(
        shop.tenantId(),
        () -> {
          for (var e :
              Map.of(
                      oldInbox,
                      "inbox_event",
                      deadInbox,
                      "inbox_event",
                      sent,
                      "outbox_event",
                      dead,
                      "outbox_event")
                  .entrySet()) {
            var row =
                jdbc.queryForMap(
                    "SELECT payload::text AS payload, payload_scrubbed_at FROM "
                        + e.getValue()
                        + " WHERE id = ?",
                    e.getKey());
            assertThat(row.get("payload_scrubbed_at")).isNotNull();
            JsonNode data = JSON.readTree(row.get("payload").toString()).path("data");
            assertThat(data.path("recipient").has("name")).isFalse();
            assertThat(data.path("recipient").has("phone")).isFalse();
            assertThat(data.path("recipient").has("address")).isFalse();
            assertThat(data.path("recipient").path("province").asString()).isEqualTo("Bangkok");
            assertThat(data.path("recipient").path("postcode").asString()).isEqualTo("10110");
            assertThat(data.path("order_id").asString()).isEqualTo("pii-test");
            assertThat(data.path("lines"))
                .isEqualTo(JSON.readTree(payload).path("data").path("lines"));
            assertThat(data.path("grand_total").asInt()).isEqualTo(100);
          }
          assertThat(
                  jdbc.queryForObject(
                      "SELECT payload_sha256 FROM inbox_event WHERE id = ?",
                      byte[].class,
                      oldInbox))
              .isEqualTo(hash);
          for (var e :
              Map.of(
                      youngInbox,
                      "inbox_event",
                      failedInbox,
                      "inbox_event",
                      receivedInbox,
                      "inbox_event",
                      youngSent,
                      "outbox_event",
                      pending,
                      "outbox_event")
                  .entrySet()) {
            var row =
                jdbc.queryForMap(
                    "SELECT payload::text AS payload, payload_scrubbed_at FROM "
                        + e.getValue()
                        + " WHERE id = ?",
                    e.getKey());
            assertThat(row.get("payload_scrubbed_at")).isNull();
            assertThat(JSON.readTree(row.get("payload").toString()))
                .isEqualTo(JSON.readTree(payload));
          }
          return null;
        });
    // The real authenticated webhook endpoint must dedupe against the original hash.
    clearInvocations(createdHandler, updatedHandler);
    var response = postEvent(eventId, payload);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).path("status").asString()).isEqualTo("duplicate");
    // Remove intentionally pending fixtures before driving the worker.
    fixture.inTenant(
        shop.tenantId(),
        () -> {
          jdbc.update("DELETE FROM inbox_event WHERE id IN (?, ?)", failedInbox, receivedInbox);
          return null;
        });
    worker.processAvailable();
    verify(createdHandler, never()).handle(argThat(message -> message.eventId().equals(eventId)));
    verify(updatedHandler, never()).handle(argThat(message -> message.eventId().equals(eventId)));
    var retry = http.post("/api/v1/outbox/" + dead + "/retry", owner.owner(), Map.of());
    assertThat(retry.status()).isEqualTo(409);
    assertThat(retry.error()).isEqualTo("PAYLOAD_SCRUBBED");
  }

  private SalesOrder cancelled(OrderFixture.Shop shop, String id) {
    return fixture.insert(
        shop,
        id + UUID.randomUUID(),
        "CANCELLED",
        "PENDING",
        "UNFULFILLED",
        "NONE",
        shop.channelAccountId());
  }

  private UUID seedEvent(
      OrderFixture.Shop shop,
      String table,
      String status,
      int days,
      String eventId,
      String payload,
      byte[] hash) {
    UUID id = UuidV7.generate();
    Timestamp at = Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS));
    fixture.inTenant(
        shop.tenantId(),
        () -> {
          if (table.equals("inbox_event"))
            jdbc.update(
                "INSERT INTO inbox_event (id, tenant_id, source, event_id, event_type, aggregate_id, aggregate_version, payload, payload_sha256, status, received_at, processed_at) VALUES (?, ?, 'TSF', ?, ?, ?, 1, ?::jsonb, ?, ?, ?, ?)",
                id,
                shop.tenantId(),
                eventId,
                JSON.readTree(payload).path("event_type").asString(),
                "aggregate-" + id,
                payload,
                hash,
                status,
                at,
                status.equals("PROCESSED") ? at : null);
          else
            jdbc.update(
                "INSERT INTO outbox_event (id, tenant_id, aggregate_type, aggregate_id, event_type, payload, status, created_at, sent_at) VALUES (?, ?, 'ORDER', ?, ?, ?::jsonb, ?, ?, ?)",
                id,
                shop.tenantId(),
                id.toString(),
                JSON.readTree(payload).path("event_type").asString(),
                payload,
                status,
                at,
                status.equals("SENT") ? at : null);
          return null;
        });
    return id;
  }

  private java.net.http.HttpResponse<String> postEvent(String id, String payload) throws Exception {
    String t = Long.toString(Instant.now().getEpochSecond());
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(
        new SecretKeySpec(
            inboxProperties.secrets().get(0).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String sig =
        "t="
            + t
            + ",v1="
            + HexFormat.of()
                .formatHex(mac.doFinal((t + "." + payload).getBytes(StandardCharsets.UTF_8)));
    String token =
        AuthTestSupport.token(
            "tsf-checkout",
            "unused",
            "ACTIVE",
            null,
            1,
            "oms-internal",
            Instant.now().plusSeconds(600),
            List.of("oms"));
    return java.net.http.HttpClient.newHttpClient()
        .send(
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://127.0.0.1:" + port + "/internal/v1/events"))
                .header("Authorization", "Bearer " + token)
                .header("X-Event-Id", id)
                .header("X-Signature", sig)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
  }

  private static String b64(String text) {
    return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }
}
