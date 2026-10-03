package com.thaishopfun.oms.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.order.OrderOptimisticLockException;
import com.thaishopfun.oms.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.node.ObjectNode;

@ActiveProfiles("test")
@SpringBootTest
@Import(OrderOptimisticLockInboxRetryTest.RetryProbeConfig.class)
class OrderOptimisticLockInboxRetryTest {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
  }

  @Autowired InboxWorker worker;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    RetryProbeConfig.calls.set(0);
    TenantContext.clear();
    try (Connection admin = AuthTestSupport.admin();
        var statement = admin.createStatement()) {
      statement.execute("TRUNCATE TABLE inbox_event CASCADE");
    } catch (SQLException ex) {
      throw new IllegalStateException(ex);
    }
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void optimisticLockExceptionIsRetriedNotDead() throws Exception {
    UUID tenant = UuidV7.generate();
    seedTenant(tenant);
    String eventId = "evt-opt-lock-" + UuidV7.generate();
    insertInbox(tenant, eventId);

    assertThat(worker.processAvailable(5)).isEqualTo(1);
    InboxRowState failed = state(eventId);
    assertThat(failed.status).isEqualTo("FAILED");
    assertThat(failed.attempts).isEqualTo(1);
    assertThat(failed.lastError).contains("version conflict");

    jdbc.update(
        "UPDATE inbox_event SET status = 'RECEIVED', next_attempt_at = now() - interval '1 second' WHERE event_id = ?",
        eventId);
    assertThat(worker.processAvailable(5)).isEqualTo(1);
    assertThat(state(eventId).status).isEqualTo("PROCESSED");
    assertThat(RetryProbeConfig.calls.get()).isEqualTo(2);
  }

  private void insertInbox(UUID tenant, String eventId) throws SQLException {
    ObjectNode payload =
        tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
    payload.put("event_type", RetryProbeConfig.EVENT_TYPE);
    payload.put("occurred_at", Instant.now().toString());
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                """
                INSERT INTO inbox_event (
                  id, tenant_id, source, event_id, event_type, aggregate_id, payload,
                  status, attempts, received_at, next_attempt_at
                ) VALUES (?, ?, 'tsf', ?, ?, ?, ?::jsonb, 'RECEIVED', 0, now(), now())
                """)) {
      statement.setObject(1, UuidV7.generate());
      statement.setObject(2, tenant);
      statement.setString(3, eventId);
      statement.setString(4, RetryProbeConfig.EVENT_TYPE);
      statement.setString(5, "agg-" + eventId);
      statement.setString(6, payload.toString());
      statement.executeUpdate();
    }
  }

  private static void seedTenant(UUID tenant) throws SQLException {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                "INSERT INTO tenant (id, name, tsf_shop_id, membership_tier, entitlement_status, ent_ver) "
                    + "VALUES (?, 't', ?, 'PRO', 'ACTIVE', 1)")) {
      statement.setObject(1, tenant);
      statement.setString(2, "shop-" + tenant);
      statement.executeUpdate();
    }
  }

  private InboxRowState state(String eventId) throws SQLException {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                "SELECT status, attempts, last_error FROM inbox_event WHERE event_id = ?")) {
      statement.setString(1, eventId);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return new InboxRowState(
            rows.getString("status"), rows.getInt("attempts"), rows.getString("last_error"));
      }
    }
  }

  private record InboxRowState(String status, int attempts, String lastError) {}

  @TestConfiguration
  static class RetryProbeConfig {
    static final AtomicInteger calls = new AtomicInteger();
    static final String EVENT_TYPE = "test.optimistic_lock";

    @Bean
    InboxHandler optimisticLockProbe() {
      return new InboxHandler() {
        @Override
        public String eventType() {
          return EVENT_TYPE;
        }

        @Override
        public void handle(InboxMessage message) {
          if (calls.incrementAndGet() == 1) {
            throw new OrderOptimisticLockException("sales_order version conflict");
          }
        }
      };
    }
  }
}
