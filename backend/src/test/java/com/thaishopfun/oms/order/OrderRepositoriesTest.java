package com.thaishopfun.oms.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.pii.PiiDecryptionException;
import com.thaishopfun.oms.tenant.TenantContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T10 repositories through the real {@code TenantAwareDataSourceTransactionManager} as {@code
 * oms_app}. Recipient PII is AES-GCM at rest; JDBC and app logs are captured at TRACE and must not
 * contain any plaintext.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=4")
// Closed after the class: every cached context holds a pool on the shared Postgres.
@DirtiesContext
class OrderRepositoriesTest {

  private static final String NAME = "สมชาย ใจดี";
  private static final String PHONE = "081-234-5678";
  private static final String ADDRESS =
      "{\"line1\":\"99/1 ถ.สุขุมวิท\",\"district\":\"คลองเตย\",\"province\":\"กรุงเทพมหานคร\"}";
  private static final List<String> SECRETS =
      List.of(NAME, "สมชาย", PHONE, "0812345678", "812345678", ADDRESS, "สุขุมวิท", "คลองเตย");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
  }

  @Autowired SalesOrderRepository orders;
  @Autowired OrderRecipientRepository recipients;
  @Autowired PlatformTransactionManager transactionManager;

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final List<Logger> raised = new ArrayList<>();
  private final List<Level> previous = new ArrayList<>();

  @BeforeEach
  void captureLogs() {
    // Step 1: Everything the app and Spring JDBC log (SQL and bind values at TRACE) is kept.
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    logs.start();
    root.addAppender(logs);
    for (String name : List.of("org.springframework.jdbc", "com.thaishopfun", "org.postgresql")) {
      Logger logger = (Logger) LoggerFactory.getLogger(name);
      raised.add(logger);
      previous.add(logger.getLevel());
      logger.setLevel(Level.TRACE);
    }
    TenantContext.clear();
  }

  @AfterEach
  void assertNoPlaintextInLogs() {
    // Step 1: Restore levels, then scan every captured message and stack trace.
    for (int i = 0; i < raised.size(); i++) {
      raised.get(i).setLevel(previous.get(i));
    }
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    root.detachAppender(logs);
    TenantContext.clear();
    assertThat(logs.list).as("JDBC TRACE logging was captured").isNotEmpty();
    for (ILoggingEvent event : logs.list) {
      String text = event.getFormattedMessage() + throwableText(event.getThrowableProxy());
      for (String secret : SECRETS) {
        assertThat(text).as("log line from %s", event.getLoggerName()).doesNotContain(secret);
      }
    }
  }

  @Test
  void salesOrderInsertAndLookups() throws SQLException {
    Shop a = shop();
    Shop b = shop();
    SalesOrder order = order(a, "TSF-1");

    // Step 1: Insert and read back by id and by (channel_account_id, external_order_id).
    as(a, () -> orders.insert(order));
    assertThat(as(a, () -> orders.findById(order.id()))).contains(order);
    assertThat(as(a, () -> orders.findByExternalId(a.channelAccount(), "TSF-1"))).contains(order);

    // Step 2: The same external id on the same channel account is rejected.
    assertThatThrownBy(() -> as(a, () -> orders.insert(order(a, "TSF-1"))))
        .isInstanceOf(DuplicateKeyException.class);

    // Step 3: Another tenant sees nothing, and cannot attach an order to A's channel account.
    assertThat(as(b, () -> orders.findById(order.id()))).isEmpty();
    assertThat(as(b, () -> orders.findByExternalId(a.channelAccount(), "TSF-1"))).isEmpty();
    SalesOrder stolen = withChannelAccount(order(b, "TSF-2"), a.channelAccount());
    assertThatThrownBy(() -> as(b, () -> orders.insert(stolen)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("sales_order_channel_account_fkey");

    // Step 4: Without a tenant context the row is invisible.
    Optional<SalesOrder> noContext =
        new TransactionTemplate(transactionManager).execute(s -> orders.findById(order.id()));
    assertThat(noContext).isEmpty();
  }

  @Test
  void recipientRoundTripsAndIsCiphertextAtRest() throws SQLException {
    Shop a = shop();
    SalesOrder order = order(a, "TSF-PII-1");
    Instant redactAfter = Instant.now().plus(90, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
    Recipient recipient = new Recipient(NAME, PHONE, ADDRESS, "กรุงเทพมหานคร", "10110");

    // Step 1: Insert and read back through the repository.
    as(
        a,
        () -> {
          orders.insert(order);
          recipients.insert(order.id(), recipient, redactAfter);
          return null;
        });
    StoredRecipient stored = as(a, () -> recipients.find(order.id())).orElseThrow();
    assertThat(stored.name()).isEqualTo(NAME);
    assertThat(stored.phone()).isEqualTo(PHONE);
    assertThat(stored.address()).isEqualTo(ADDRESS);
    assertThat(stored.phoneLast4()).isEqualTo("5678");
    assertThat(stored.province()).isEqualTo("กรุงเทพมหานคร");
    assertThat(stored.postcode()).isEqualTo("10110");
    assertThat(stored.piiStatus()).isEqualTo("ACTIVE");
    assertThat(stored.redactAfter()).isEqualTo(redactAfter);
    assertThat(stored.toString()).doesNotContain(NAME).doesNotContain(PHONE);

    // Step 2: The raw row holds no plaintext, in any column or encoding.
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                "SELECT row_to_json(r)::text AS json, name_enc, phone_enc, address_enc, "
                    + "phone_hash FROM order_recipient r WHERE order_id = ?")) {
      statement.setObject(1, order.id());
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        String json = rows.getString("json");
        StringBuilder raw = new StringBuilder(json);
        for (String column : List.of("name_enc", "phone_enc", "address_enc")) {
          byte[] value = rows.getBytes(column);
          assertThat(value).as(column).isNotNull();
          assertThat(value[0]).as(column + " version").isEqualTo((byte) 1);
          raw.append(new String(value, StandardCharsets.UTF_8))
              .append(HexFormat.of().formatHex(value));
        }
        assertThat(rows.getBytes("phone_hash")).hasSize(32);
        for (String secret : List.of(NAME, "สมชาย", PHONE, "0812345678", "812345678", "สุขุมวิท")) {
          assertThat(raw.toString()).doesNotContain(secret);
          assertThat(raw.toString())
              .doesNotContain(HexFormat.of().formatHex(secret.getBytes(StandardCharsets.UTF_8)));
        }
      }
    }

    // Step 3: The same ciphertext copied onto another order's row fails to decrypt (AAD).
    SalesOrder other = order(a, "TSF-PII-2");
    as(
        a,
        () -> {
          orders.insert(other);
          recipients.insert(other.id(), new Recipient("x", null, "y", "Bangkok", "10110"), null);
          return null;
        });
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                "UPDATE order_recipient SET name_enc = "
                    + "(SELECT name_enc FROM order_recipient WHERE order_id = ?) WHERE order_id = ?")) {
      statement.setObject(1, order.id());
      statement.setObject(2, other.id());
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
    assertThatThrownBy(() -> as(a, () -> recipients.find(other.id())))
        .isInstanceOf(PiiDecryptionException.class)
        .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(NAME));
  }

  @Test
  void redactionLeavesOnlyProvinceAndPostcode() throws SQLException {
    Shop a = shop();
    SalesOrder order = order(a, "TSF-RED-1");
    SalesOrder kept = order(a, "TSF-RED-2");
    as(
        a,
        () -> {
          orders.insert(order);
          orders.insert(kept);
          recipients.insert(
              order.id(), new Recipient(NAME, PHONE, ADDRESS, "Bangkok", "10110"), null);
          recipients.insert(
              kept.id(), new Recipient(NAME, PHONE, ADDRESS, "Bangkok", "10110"), null);
          return null;
        });
    assertThat(as(a, () -> recipients.findOrderIdsByPhone(PHONE))).hasSize(2);

    // Step 1: Redact. Only province and postcode remain; phone_last4 is gone too.
    assertThat(as(a, () -> recipients.redact(order.id()))).isTrue();
    StoredRecipient redacted = as(a, () -> recipients.find(order.id())).orElseThrow();
    assertThat(redacted.redacted()).isTrue();
    assertThat(redacted.name()).isNull();
    assertThat(redacted.phone()).isNull();
    assertThat(redacted.address()).isNull();
    assertThat(redacted.phoneLast4()).isNull();
    assertThat(redacted.province()).isEqualTo("Bangkok");
    assertThat(redacted.postcode()).isEqualTo("10110");

    // Step 2: The raw row has no phone_hash either, so no searchable identifier is left.
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                "SELECT phone_hash, phone_last4 FROM order_recipient WHERE order_id = ?")) {
      statement.setObject(1, order.id());
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getBytes("phone_hash")).isNull();
        assertThat(rows.getString("phone_last4")).isNull();
      }
    }

    // Step 3: Phone lookup, in any format, no longer returns the redacted order.
    for (String phone : List.of(PHONE, "+66812345678", "0812345678")) {
      assertThat(as(a, () -> recipients.findOrderIdsByPhone(phone)))
          .as(phone)
          .containsExactly(kept.id());
    }

    // Step 4: Redacting an order that has no visible recipient reports false.
    assertThat(as(shop(), () -> recipients.redact(order.id()))).isFalse();
  }

  @Test
  void phoneLookupIsFormatIndependentAndTenantScoped() {
    Shop a = shop();
    Shop b = shop();
    SalesOrder first = order(a, "TSF-PH-1");
    SalesOrder second = order(a, "TSF-PH-2");
    SalesOrder elsewhere = order(b, "TSF-PH-3");
    as(
        a,
        () -> {
          orders.insert(first);
          orders.insert(second);
          recipients.insert(first.id(), new Recipient(NAME, "0812345678", ADDRESS, "B", "1"), null);
          recipients.insert(
              second.id(), new Recipient(NAME, "+66 81 234 5678", ADDRESS, "B", "1"), null);
          return null;
        });
    as(
        b,
        () -> {
          orders.insert(elsewhere);
          recipients.insert(elsewhere.id(), new Recipient(NAME, PHONE, ADDRESS, "B", "1"), null);
          return null;
        });

    // Step 1: Any format finds both of A's orders. B's order with the same phone is not listed.
    List<UUID> expected = new ArrayList<>(List.of(first.id(), second.id()));
    expected.sort(null);
    for (String phone : List.of(PHONE, "+66812345678", "66812345678")) {
      assertThat(as(a, () -> recipients.findOrderIdsByPhone(phone)))
          .containsExactlyElementsOf(expected);
    }
    assertThat(as(b, () -> recipients.findOrderIdsByPhone(PHONE))).containsExactly(elsewhere.id());
    assertThat(as(a, () -> recipients.findOrderIdsByPhone("0899999999"))).isEmpty();

    // Step 2: B cannot read A's recipient.
    assertThat(as(b, () -> recipients.find(first.id()))).isEmpty();
  }

  private <T> T as(Shop shop, Supplier<T> call) {
    TenantContext.set(shop.tenant(), null);
    try {
      return new TransactionTemplate(transactionManager).execute(status -> call.get());
    } finally {
      TenantContext.clear();
    }
  }

  private void as(Shop shop, Runnable call) {
    as(
        shop,
        () -> {
          call.run();
          return null;
        });
  }

  private static SalesOrder order(Shop shop, String externalId) {
    return new SalesOrder(
        UuidV7.generate(),
        shop.tenant(),
        shop.channelAccount(),
        externalId,
        "ACTIVE",
        "PENDING",
        "UNFULFILLED",
        "NONE",
        null,
        "UNPAID",
        "PREPAID",
        "THB",
        new BigDecimal("590.00"),
        new BigDecimal("40.00"),
        new BigDecimal("50.00"),
        new BigDecimal("580.00"),
        Instant.now().truncatedTo(ChronoUnit.MICROS),
        null,
        Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS),
        7L,
        0);
  }

  private static SalesOrder withChannelAccount(SalesOrder o, UUID channelAccount) {
    return new SalesOrder(
        o.id(),
        o.tenantId(),
        channelAccount,
        o.externalOrderId(),
        o.orderStatus(),
        o.paymentStatus(),
        o.fulfillmentStatus(),
        o.holdReason(),
        o.holdNote(),
        o.channelStatus(),
        o.paymentMethod(),
        o.currency(),
        o.subtotal(),
        o.shippingFee(),
        o.discount(),
        o.grandTotal(),
        o.orderedAt(),
        o.paidAt(),
        o.shipBy(),
        o.externalVersion(),
        o.version());
  }

  private static Shop shop() {
    UUID tenant = UuidV7.generate();
    UUID channelAccount = UuidV7.generate();
    try (Connection admin = AuthTestSupport.admin()) {
      exec(
          admin,
          "INSERT INTO tenant (id, name, tsf_shop_id, membership_tier, entitlement_status, "
              + "ent_ver) VALUES (?, 'Shop', ?, 'PRO', 'ACTIVE', 1)",
          tenant,
          "shop-" + tenant);
      exec(
          admin,
          "INSERT INTO channel_account (id, tenant_id, channel, external_shop_id, status) "
              + "VALUES (?, ?, 'TSF', ?, 'CONNECTED')",
          channelAccount,
          tenant,
          "shop-" + tenant);
    } catch (SQLException ex) {
      throw new IllegalStateException(ex);
    }
    return new Shop(tenant, channelAccount);
  }

  private static void exec(Connection connection, String sql, Object... params)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      statement.executeUpdate();
    }
  }

  private static String throwableText(IThrowableProxy proxy) {
    StringBuilder text = new StringBuilder();
    for (IThrowableProxy current = proxy; current != null; current = current.getCause()) {
      text.append(' ').append(current.getClassName()).append(": ").append(current.getMessage());
    }
    return text.toString();
  }

  private record Shop(UUID tenant, UUID channelAccount) {}
}
