package com.thaishopfun.oms.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.MockTsfApplication;
import com.thaishopfun.mocktsf.OmsEndpoint;
import com.thaishopfun.mocktsf.contract.ContractValidator;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.fulfillment.FulfillmentTestSupport;
import com.thaishopfun.oms.inbox.InboxWorker;
import com.thaishopfun.oms.invariant.SkipInvariantCheck;
import com.thaishopfun.oms.order.OrderIntakeScenarioSupport;
import com.thaishopfun.oms.order.SalesOrder;
import com.thaishopfun.oms.order.SalesOrderRepository;
import com.thaishopfun.oms.order.returnflow.ReturnRepository;
import com.thaishopfun.oms.stock.StockFixture;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@ActiveProfiles("test")
@SkipInvariantCheck("Return acceptance seeds inventory without opening ledger rows")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "oms.outbox.publisher-enabled=false")
class ReturnFlowAcceptanceTest extends OrderIntegrationTest {

  private static final String INBOX_SECRET = "dev-inbox-hmac-secret";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final ContractValidator CONTRACT = ContractValidator.classpath();
  private static ConfigurableApplicationContext mock;
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Autowired SalesOrderRepository orders;
  @Autowired InboxWorker worker;
  @Autowired JdbcTemplate jdbc;
  @Autowired ReturnRepository returns;
  @Autowired PlatformTransactionManager transactions;

  StockFixture fixture;
  String tsfShopId;
  UUID tenantId;
  UUID channelAccountId;
  UUID sku7781;
  UUID sku9001;

  @DynamicPropertySource
  static void mockTsf(DynamicPropertyRegistry registry) {
    startMock();
    int mockPort = mockPort();
    registry.add("oms.tsf.base-url", () -> "http://127.0.0.1:" + mockPort);
    registry.add("oms.tsf.token-uri", () -> "http://127.0.0.1:" + mockPort + "/tsf-idp/token");
    registry.add("oms.tsf.client-id", () -> "oms-service");
    registry.add("oms.tsf.client-secret", () -> "dev-oms-service-secret");
    registry.add("oms.tsf.audience", () -> "tsf-internal");
    registry.add("oms.inbox.hmac-secrets", () -> INBOX_SECRET);
    registry.add("oms.inbox.worker-enabled", () -> "false");
    registry.add("oms.inbox.jitter-ratio", () -> "0");
    registry.add("oms.security.internal-client-ids", () -> "tsf,tsf-checkout");
  }

  @AfterAll
  static void stopMock() {
    if (mock != null) {
      mock.close();
    }
  }

  @BeforeEach
  void resetInbox() throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        var statement = admin.createStatement()) {
      statement.execute("TRUNCATE TABLE inbox_event");
    }
    mock.getBean(OmsEndpoint.class).setBaseUrl("http://127.0.0.1:" + port);
    fixture = new StockFixture(jdbc, transactions);
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    tenantId = shop.tenant();
    tsfShopId = fixture.tsfShopId(shop);
    UUID account = fixture.channelAccount(shop, tsfShopId, "ACTIVE", "CONNECTED");
    channelAccountId = account;
    sku7781 = fixture.sku(shop, 30);
    sku9001 = fixture.sku(shop, 30);
    fixture.channelListing(shop, account, "tsf_sku_7781", sku7781, true);
    fixture.channelListing(shop, account, "tsf_sku_9001", sku9001, true);
  }

  @Test
  void partialReturnKeepsOrderActiveDeliveredAndRestocksResellable() throws Exception {
    SalesOrder order = seedDeliveredTwoLineOrder();
    UUID sku = fixture.inTenant(tenantId, () -> lineSku(order.id(), "L1"));
    FulfillmentTestSupport.seedInventory(
        order.tenantId(), sku, FulfillmentTestSupport.ensureDefaultWarehouse(order.tenantId()), 10);
    ingest(returnRequested(order.externalOrderId(), "L1", 1));
    ingest(returnDecided(order.externalOrderId(), "APPROVED"));
    UUID returnId =
        fixture.inTenant(
            tenantId,
            () -> returns.findByExternalId(order.id(), "tsf_ret_partial").orElseThrow().id());
    UUID returnLine =
        fixture.inTenant(tenantId, () -> returnLineId(returnId, "L1"));
    int before = fixture.inTenant(tenantId, () -> onHand(sku));
    String body =
        """
        {"lines":[{"return_line_id":"%s","condition":"RESELLABLE","qty":1}]}
        """
            .formatted(returnLine);
    assertThat(postReceive(returnId, body).statusCode()).isEqualTo(200);
    SalesOrder fresh = fixture.inTenant(tenantId, () -> orders.findById(order.id()).orElseThrow());
    assertThat(fresh.orderStatus()).isEqualTo("ACTIVE");
    assertThat(fresh.fulfillmentStatus()).isEqualTo("DELIVERED");
    assertThat(fixture.inTenant(tenantId, () -> onHand(sku))).isEqualTo(before + 1);
    assertThat(postReceive(returnId, body).statusCode()).isEqualTo(200);
    assertThat(fixture.inTenant(tenantId, () -> onHand(sku))).isEqualTo(before + 1);
  }

  @Test
  void damagedReturnDoesNotChangeOnHand() throws Exception {
    SalesOrder order = seedDeliveredTwoLineOrder();
    UUID sku = fixture.inTenant(tenantId, () -> lineSku(order.id(), "L1"));
    FulfillmentTestSupport.seedInventory(
        order.tenantId(), sku, FulfillmentTestSupport.ensureDefaultWarehouse(order.tenantId()), 10);
    ingest(returnRequested(order.externalOrderId(), "L1", 1));
    ingest(returnDecided(order.externalOrderId(), "APPROVED"));
    UUID returnId =
        fixture.inTenant(
            tenantId,
            () -> returns.findByExternalId(order.id(), "tsf_ret_partial").orElseThrow().id());
    int before = fixture.inTenant(tenantId, () -> onHand(sku));
    String body =
        """
        {"lines":[{"return_line_id":"%s","condition":"DAMAGED","qty":1}]}
        """
            .formatted(fixture.inTenant(tenantId, () -> returnLineId(returnId, "L1")));
    assertThat(postReceive(returnId, body).statusCode()).isEqualTo(200);
    assertThat(fixture.inTenant(tenantId, () -> onHand(sku))).isEqualTo(before);
  }

  @Test
  void mixedConditionPerPieceRestocksOnlyResellableUnits() throws Exception {
    SalesOrder order = seedDeliveredSingleLineQty(2);
    UUID sku = fixture.inTenant(tenantId, () -> lineSku(order.id(), "L1"));
    int startOnHand = 10;
    FulfillmentTestSupport.seedInventory(
        order.tenantId(), sku, FulfillmentTestSupport.ensureDefaultWarehouse(order.tenantId()), startOnHand);
    simulateShippedUnits(order, sku, 2);
    ingest(returnRequested(order.externalOrderId(), "L1", 2));
    ingest(returnDecided(order.externalOrderId(), "APPROVED"));
    UUID returnId =
        fixture.inTenant(
            tenantId,
            () -> returns.findByExternalId(order.id(), "tsf_ret_partial").orElseThrow().id());
    UUID returnLine =
        fixture.inTenant(tenantId, () -> returnLineId(returnId, "L1"));
    String body =
        """
        {"lines":[
          {"return_line_id":"%s","condition":"RESELLABLE","qty":1},
          {"return_line_id":"%s","condition":"DAMAGED","qty":1}
        ]}
        """
            .formatted(returnLine, returnLine);
    assertThat(postReceive(returnId, body).statusCode()).isEqualTo(200);
    assertThat(fixture.inTenant(tenantId, () -> onHand(sku))).isEqualTo(startOnHand - 2 + 1);
  }

  @Test
  void partialLineReceiveKeepsReturnApprovedUntilAllLinesDone() throws Exception {
    SalesOrder order = seedDeliveredTwoLineOrder();
    UUID sku1 = fixture.inTenant(tenantId, () -> lineSku(order.id(), "L1"));
    UUID sku2 = fixture.inTenant(tenantId, () -> lineSku(order.id(), "L2"));
    FulfillmentTestSupport.seedInventory(
        order.tenantId(), sku1, FulfillmentTestSupport.ensureDefaultWarehouse(order.tenantId()), 5);
    FulfillmentTestSupport.seedInventory(
        order.tenantId(), sku2, FulfillmentTestSupport.ensureDefaultWarehouse(order.tenantId()), 5);
    ingest(returnRequestedTwoLines(order.externalOrderId(), 1, 1));
    ingest(returnDecided(order.externalOrderId(), "APPROVED"));
    UUID returnId =
        fixture.inTenant(
            tenantId,
            () -> returns.findByExternalId(order.id(), "tsf_ret_partial").orElseThrow().id());
    UUID line1 = fixture.inTenant(tenantId, () -> returnLineId(returnId, "L1"));
    UUID line2 = fixture.inTenant(tenantId, () -> returnLineId(returnId, "L2"));
    String first =
        """
        {"lines":[{"return_line_id":"%s","condition":"RESELLABLE","qty":1}]}
        """
            .formatted(line1);
    assertThat(postReceive(returnId, first).statusCode()).isEqualTo(200);
    assertThat(
            fixture.inTenant(
                tenantId,
                () ->
                    returns
                        .findByExternalId(order.id(), "tsf_ret_partial")
                        .orElseThrow()
                        .status()))
        .isEqualTo("APPROVED");
    String second =
        """
        {"lines":[{"return_line_id":"%s","condition":"RESELLABLE","qty":1}]}
        """
            .formatted(line2);
    assertThat(postReceive(returnId, second).statusCode()).isEqualTo(200);
    assertThat(
            fixture.inTenant(
                tenantId,
                () ->
                    returns
                        .findByExternalId(order.id(), "tsf_ret_partial")
                        .orElseThrow()
                        .status()))
        .isEqualTo("RECEIVED");
  }

  @Test
  void cumulativeReturnQtyFromTriggerMarksInboxDead() throws Exception {
    SalesOrder order = seedDeliveredSingleLineQty(2);
    ingest(returnRequested(order.externalOrderId(), "L1", 1, "ret-a"));
    ingest(returnDecided(order.externalOrderId(), "APPROVED", "ret-a"));
    ObjectNode second = returnRequested(order.externalOrderId(), "L1", 2, "ret-b");
    second.put("event_id", "ret-cum-" + UUID.randomUUID());
    ingestOnly(second);
    worker.processAvailable(1);
    assertThat(
            fixture.inTenant(
                tenantId,
                () ->
                    jdbc.queryForObject(
                        "SELECT status FROM inbox_event WHERE event_id = ?",
                        String.class,
                        second.path("event_id").asString())))
        .isEqualTo("DEAD");
  }

  @Test
  void returnQtyAboveShippedMarksInboxDead() throws Exception {
    SalesOrder order = seedPaidOneLineOrder();
    ObjectNode event = returnRequested(order.externalOrderId(), "L1", 1);
    event.put("event_id", "ret-oos-" + UUID.randomUUID());
    ingestOnly(event);
    worker.processAvailable(1);
    assertThat(
            fixture.inTenant(
                tenantId,
                () ->
                    jdbc.queryForObject(
                        "SELECT status FROM inbox_event WHERE event_id = ?",
                        String.class,
                        event.path("event_id").asString())))
        .isEqualTo("DEAD");
  }

  private SalesOrder seedPaidOneLineOrder() throws Exception {
    String external = "RET-ONE-" + UUID.randomUUID().toString().substring(0, 8);
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON,
            external,
            tsfShopId,
            UUID.randomUUID().toString(),
            "PREPAID",
            "tsf_sku_7781",
            2,
            1));
    ingest(OrderIntakeScenarioSupport.orderPaid(JSON, external, tsfShopId, 2));
    return fixture.inTenant(
        tenantId, () -> orders.findByExternalId(channelAccountId, external).orElseThrow());
  }

  private SalesOrder seedDeliveredTwoLineOrder() throws Exception {
    String external = "RET-TWO-" + UUID.randomUUID().toString().substring(0, 8);
    ingest(
        OrderIntakeScenarioSupport.orderCreatedTwoLines(
            JSON,
            external,
            tsfShopId,
            UUID.randomUUID().toString(),
            "PREPAID",
            "tsf_sku_7781",
            "tsf_sku_9001",
            2,
            1,
            1));
    ingest(OrderIntakeScenarioSupport.orderPaid(JSON, external, tsfShopId, 2));
    SalesOrder order =
        fixture.inTenant(
            tenantId, () -> orders.findByExternalId(channelAccountId, external).orElseThrow());
    fixture.inTenant(
        tenantId,
        () -> {
          jdbc.update(
              """
              UPDATE sales_order
              SET fulfillment_status = 'DELIVERED', payment_status = 'PAID', order_status = 'ACTIVE'
              WHERE id = ?
              """,
              order.id());
          return null;
        });
    return fixture.inTenant(tenantId, () -> orders.findById(order.id()).orElseThrow());
  }

  private HttpResponse<String> postReceive(UUID returnId, String jsonBody) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/returns/" + returnId + "/receive"))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + ownerToken(tsfShopId))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private SalesOrder seedDeliveredSingleLineQty(int lineQty) throws Exception {
    String external = "RET-ONEQ-" + UUID.randomUUID().toString().substring(0, 8);
    ingest(
        OrderIntakeScenarioSupport.orderCreated(
            JSON,
            external,
            tsfShopId,
            UUID.randomUUID().toString(),
            "PREPAID",
            "tsf_sku_7781",
            lineQty,
            1));
    ingest(OrderIntakeScenarioSupport.orderPaid(JSON, external, tsfShopId, 2));
    SalesOrder order =
        fixture.inTenant(
            tenantId, () -> orders.findByExternalId(channelAccountId, external).orElseThrow());
    fixture.inTenant(
        tenantId,
        () -> {
          jdbc.update(
              """
              UPDATE sales_order
              SET fulfillment_status = 'DELIVERED', payment_status = 'PAID', order_status = 'ACTIVE'
              WHERE id = ?
              """,
              order.id());
          return null;
        });
    return fixture.inTenant(tenantId, () -> orders.findById(order.id()).orElseThrow());
  }

  private void simulateShippedUnits(SalesOrder order, UUID skuId, int shippedQty) {
    fixture.inTenant(
        tenantId,
        () -> {
          jdbc.update(
              """
              UPDATE inventory
              SET on_hand = on_hand - ?
              WHERE tenant_id = ? AND sku_id = ?
              """,
              shippedQty,
              order.tenantId(),
              skuId);
          return null;
        });
  }

  private ObjectNode returnRequestedTwoLines(String externalOrderId, int qtyL1, int qtyL2)
      throws IOException {
    return returnRequestedTwoLines(externalOrderId, qtyL1, qtyL2, "tsf_ret_partial");
  }

  private ObjectNode returnRequestedTwoLines(
      String externalOrderId, int qtyL1, int qtyL2, String externalReturnId) throws IOException {
    ObjectNode event = loadExample("return.requested.json");
    event.put("event_id", "ret-req-" + UUID.randomUUID());
    event.put("tsf_shop_id", tsfShopId);
    event.put("aggregate_id", externalReturnId);
    ObjectNode data = (ObjectNode) event.get("data");
    data.put("order_id", externalOrderId);
    data.put("return_id", externalReturnId);
    ArrayNode lines = JSON.createArrayNode();
    ObjectNode l1 = JSON.createObjectNode();
    l1.put("line_id", "L1");
    l1.put("qty", qtyL1);
    lines.add(l1);
    ObjectNode l2 = JSON.createObjectNode();
    l2.put("line_id", "L2");
    l2.put("qty", qtyL2);
    lines.add(l2);
    data.set("lines", lines);
    assertThat(CONTRACT.envelopeErrors(JSON.writeValueAsString(event))).isEmpty();
    return event;
  }

  private ObjectNode returnRequested(String externalOrderId, String lineId, int qty)
      throws IOException {
    return returnRequested(externalOrderId, lineId, qty, "tsf_ret_partial");
  }

  private ObjectNode returnRequested(
      String externalOrderId, String lineId, int qty, String externalReturnId) throws IOException {
    ObjectNode event = loadExample("return.requested.json");
    event.put("event_id", "ret-req-" + UUID.randomUUID());
    event.put("tsf_shop_id", tsfShopId);
    event.put("aggregate_id", externalReturnId);
    ObjectNode data = (ObjectNode) event.get("data");
    data.put("order_id", externalOrderId);
    data.put("return_id", externalReturnId);
    ArrayNode lines = JSON.createArrayNode();
    ObjectNode line = JSON.createObjectNode();
    line.put("line_id", lineId);
    line.put("qty", qty);
    lines.add(line);
    data.set("lines", lines);
    assertThat(CONTRACT.envelopeErrors(JSON.writeValueAsString(event))).isEmpty();
    return event;
  }

  private ObjectNode returnDecided(String externalOrderId, String decision) throws IOException {
    return returnDecided(externalOrderId, decision, "tsf_ret_partial");
  }

  private ObjectNode returnDecided(String externalOrderId, String decision, String externalReturnId)
      throws IOException {
    ObjectNode event = loadExample("return.decided.json");
    event.put("event_id", "ret-dec-" + UUID.randomUUID());
    event.put("tsf_shop_id", tsfShopId);
    event.put("aggregate_id", externalReturnId);
    ObjectNode data = (ObjectNode) event.get("data");
    data.put("order_id", externalOrderId);
    data.put("return_id", externalReturnId);
    data.put("decision", decision);
    assertThat(CONTRACT.envelopeErrors(JSON.writeValueAsString(event))).isEmpty();
    return event;
  }

  private ObjectNode orderCreated(String external, long version) throws IOException {
    ObjectNode event = loadExample("order.created.json");
    event.put("event_id", "oc-" + external);
    event.put("tsf_shop_id", tsfShopId);
    event.put("aggregate_id", external);
    event.put("aggregate_version", version);
    ObjectNode data = (ObjectNode) event.get("data");
    data.put("order_id", external);
    data.put("reservation_id", UUID.randomUUID().toString());
    ArrayNode lines = JSON.createArrayNode();
    ObjectNode line = JSON.createObjectNode();
    line.put("line_id", "L1");
    line.put("listing_sku_id", "tsf_sku_7781");
    line.put("seller_sku", "TSHIRT-BLK-M");
    line.put("name", "เสื้อ");
    line.put("qty", 2);
    line.put("unit_price", 100);
    lines.add(line);
    data.set("lines", lines);
    return event;
  }

  private ObjectNode orderCreatedTwoLines(String external, long version) throws IOException {
    ObjectNode event = orderCreated(external, version);
    ArrayNode lines = JSON.createArrayNode();
    ObjectNode l1 = JSON.createObjectNode();
    l1.put("line_id", "L1");
    l1.put("listing_sku_id", "tsf_sku_7781");
    l1.put("seller_sku", "TSHIRT-BLK-M");
    l1.put("name", "เสื้อ A");
    l1.put("qty", 2);
    l1.put("unit_price", 100);
    lines.add(l1);
    ObjectNode l2 = JSON.createObjectNode();
    l2.put("line_id", "L2");
    l2.put("listing_sku_id", "tsf_sku_9001");
    l2.put("seller_sku", "MUG-WHT");
    l2.put("name", "แก้ว");
    l2.put("qty", 1);
    l2.put("unit_price", 50);
    lines.add(l2);
    ((ObjectNode) event.get("data")).set("lines", lines);
    return event;
  }

  private ObjectNode orderPaid(String external, long version) throws IOException {
    ObjectNode event = loadExample("order.paid.json");
    event.put("event_id", "op-" + external);
    event.put("tsf_shop_id", tsfShopId);
    event.put("aggregate_id", external);
    event.put("aggregate_version", version);
    ((ObjectNode) event.get("data")).put("order_id", external);
    return event;
  }

  private void ingest(ObjectNode event) throws Exception {
    ingestOnly(event);
    assertThat(worker.processAvailable(1)).isEqualTo(1);
  }

  private void ingestOnly(ObjectNode event) throws Exception {
    byte[] body = JSON.writeValueAsBytes(event);
    String eventId = event.path("event_id").asString();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/events"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("X-Event-Id", eventId)
            .header("X-Signature", sign(INBOX_SECRET, now(), body))
            .header("Authorization", "Bearer " + tsfToken())
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    assertThat(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(202);
  }

  private UUID lineSku(UUID orderId, String externalLineId) {
    return jdbc.queryForObject(
        """
        SELECT sku_id FROM order_line
        WHERE order_id = ? AND external_line_id = ?
        """,
        UUID.class,
        orderId,
        externalLineId);
  }

  private UUID returnLineId(UUID returnId, String externalLineId) {
    return jdbc.queryForObject(
        """
        SELECT rl.id FROM return_line rl
        JOIN order_line ol ON ol.id = rl.order_line_id
        WHERE rl.return_id = ? AND ol.external_line_id = ?
        """,
        UUID.class,
        returnId,
        externalLineId);
  }

  private int onHand(UUID skuId) {
    Integer value =
        jdbc.queryForObject(
            """
            SELECT on_hand FROM inventory WHERE tenant_id = ? AND sku_id = ?
            ORDER BY warehouse_id LIMIT 1
            """,
            Integer.class,
            tenantId,
            skuId);
    return value == null ? 0 : value;
  }

  private static ObjectNode loadExample(String name) throws IOException {
    try (InputStream in =
        MockTsfApplication.class.getResourceAsStream("/contracts/examples/events/" + name)) {
      return (ObjectNode) JSON.readTree(in);
    }
  }

  private static String ownerToken(String shopId) {
    return AuthTestSupport.token(
        "owner@shop.example",
        shopId,
        "ACTIVE",
        null,
        1,
        "oms",
        Instant.now().plusSeconds(600),
        java.util.List.of("oms"),
        "OWNER");
  }

  private static String tsfToken() {
    return AuthTestSupport.token(
        "tsf-checkout",
        "shop",
        "ACTIVE",
        null,
        1,
        "oms-internal",
        Instant.now().plusSeconds(600),
        java.util.List.of(),
        "SERVICE");
  }

  private static String sign(String secret, String timestamp, byte[] body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
    mac.update(body);
    return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal());
  }

  private static String now() {
    return Long.toString(Instant.now().getEpochSecond());
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
            "--mock.issuer=http://mock-return.test/tsf-idp",
            "--spring.main.banner-mode=off",
            "--spring.main.register-shutdown-hook=false");
  }

  private static int mockPort() {
    return Integer.parseInt(mock.getEnvironment().getProperty("local.server.port"));
  }
}
