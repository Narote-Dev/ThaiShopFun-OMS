package com.thaishopfun.oms.stock;

import com.thaishopfun.oms.order.OrderIntakeHooks;
import com.thaishopfun.oms.stock.StockTestConfig.FaultHooks;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fault injection for intake acceptance without replacing the application {@link java.time.Clock}.
 */
@TestConfiguration
public class OrderIntakeFaultTestConfig {

  /** When set, the next {@link OrderIntakeHooks#beforeEngineWrite()} raises SQLSTATE 40P01 once. */
  public static final AtomicBoolean injectDeadlockOnce = new AtomicBoolean(false);

  /** Set when {@link #maybeInjectDeadlock} consumed {@link #injectDeadlockOnce}. */
  public static final AtomicBoolean injectDeadlockConsumed = new AtomicBoolean(false);

  public static void maybeInjectDeadlock(JdbcTemplate jdbc) {
    if (injectDeadlockOnce.compareAndSet(true, false)) {
      injectDeadlockConsumed.set(true);
      jdbc.execute("DO $$ BEGIN RAISE EXCEPTION USING ERRCODE = '40P01'; END $$;");
    }
  }

  @Bean
  @Primary
  FaultHooks intakeFaultHooks(JdbcTemplate jdbc) {
    return new FaultHooks(jdbc);
  }
}
