package com.thaishopfun.oms.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.stock.ReservationEngine;
import com.thaishopfun.oms.stock.ReserveItem;
import com.thaishopfun.oms.stock.StockFixture;
import com.thaishopfun.oms.stock.StockOwner;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

@ActiveProfiles("test")
@SpringBootTest
class FlywayV9Test {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired ReservationEngine engine;

  @Test
  void resolveReservationTenantReturnsIdOnly() throws Exception {
    StockFixture fixture = new StockFixture(jdbc, transactions);
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    UUID sku = fixture.sku(shop, 5);
    UUID group =
        fixture.inTenant(
            shop.tenant(),
            () ->
                engine
                    .reserve(StockOwner.checkout("chk-v9"), List.of(ReserveItem.of(sku, 1)), "k-v9")
                    .reservationGroupId());
    try (Connection app = AuthTestSupport.app();
        PreparedStatement statement =
            app.prepareStatement("SELECT resolve_reservation_tenant(?)")) {
      statement.setObject(1, group);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getObject(1, UUID.class)).isEqualTo(shop.tenant());
      }
    }
    try (Connection app = AuthTestSupport.app();
        PreparedStatement statement =
            app.prepareStatement("SELECT resolve_reservation_tenant(?)")) {
      statement.setObject(1, UuidV7.generate());
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getObject(1, UUID.class)).isNull();
      }
    }
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement =
            admin.prepareStatement(
                """
                SELECT pg_get_userbyid(p.proowner) AS owner,
                       has_function_privilege('PUBLIC', 'public.resolve_reservation_tenant(uuid)', 'EXECUTE') AS public_exec,
                       has_function_privilege('oms_app', 'public.resolve_reservation_tenant(uuid)', 'EXECUTE') AS app_exec
                FROM pg_proc AS p
                JOIN pg_namespace AS n ON n.oid = p.pronamespace
                WHERE n.nspname = 'public' AND p.proname = 'resolve_reservation_tenant'
                """)) {
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("owner")).isEqualTo("oms_maint");
        assertThat(rs.getBoolean("public_exec")).isFalse();
        assertThat(rs.getBoolean("app_exec")).isTrue();
      }
    }
  }
}
