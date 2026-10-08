package com.thaishopfun.oms.stock;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.invariant.VerifyInvariants;
import com.thaishopfun.oms.stock.StockTestConfig.FaultHooks;
import com.thaishopfun.oms.stock.StockTestConfig.MutableClock;
import com.thaishopfun.oms.stock.StockTestConfig.StockEventRecorder;
import com.thaishopfun.oms.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Shared context for the engine tests: the real {@code TenantAwareDataSourceTransactionManager},
 * runtime role {@code oms_app}, Postgres 16 from {@link AuthTestSupport}. Every test works in fresh
 * tenants, so the shared database needs no reset.
 */
@ActiveProfiles("test")
@SpringBootTest(
    properties = {
      "spring.datasource.hikari.maximum-pool-size=20",
      "spring.datasource.hikari.minimum-idle=2"
    })
@Import(StockTestConfig.class)
// Closed after the class: every cached context holds a pool on the shared Postgres.
@DirtiesContext
@VerifyInvariants
public abstract class StockTestBase {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
  }

  @Autowired ReservationEngine engine;
  @Autowired StockAvailability availability;
  @Autowired StockExpiryJob expiryJob;
  @Autowired StockProperties properties;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired MutableClock clock;
  @Autowired FaultHooks faults;
  @Autowired StockEventRecorder events;
  @Autowired MeterRegistry meters;

  protected StockFixture fixture;

  @BeforeEach
  void fixture() {
    fixture = new StockFixture(jdbc, transactions);
    faults.reset();
    TenantContext.clear();
  }

  @AfterEach
  void clearContext() {
    faults.reset();
    TenantContext.clear();
  }

  /** Runs an engine call as the shop, the way a request thread or a handler would. */
  protected <T> T as(StockFixture.Shop shop, Supplier<T> call) {
    TenantContext.set(shop.tenant(), null);
    try {
      return call.get();
    } finally {
      TenantContext.clear();
    }
  }

  double counter(String name, String cause) {
    var counter = meters.find(name).tag("cause", cause).counter();
    return counter == null ? 0 : counter.count();
  }
}
