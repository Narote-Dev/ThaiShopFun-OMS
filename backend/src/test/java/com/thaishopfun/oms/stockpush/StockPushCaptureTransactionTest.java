package com.thaishopfun.oms.stockpush;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.thaishopfun.oms.outbox.OutboxAppender;
import com.thaishopfun.oms.stock.StockAvailability;
import com.thaishopfun.oms.stock.StockChanged;
import com.thaishopfun.oms.tenant.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Real Spring events, transactions and JDBC resource binding; the JDBC driver alone is mocked. */
class StockPushCaptureTransactionTest {
  private final DataSource pool = mock(DataSource.class);
  private final Connection connection = mock(Connection.class);
  private final PreparedStatement marker = mock(PreparedStatement.class);
  private final AtomicInteger borrows = new AtomicInteger();
  private final List<String> order = new ArrayList<>();
  private final UUID tenant = UUID.randomUUID();
  private final StockChanged change = new StockChanged(tenant, Set.of(UUID.randomUUID()), Set.of());
  private AnnotationConfigApplicationContext context;
  private TransactionTemplate tx;

  @BeforeEach
  void setup() throws Exception {
    // A second borrow while the stock connection is held must fail immediately, not hang CI.
    when(pool.getConnection())
        .thenAnswer(
            call -> {
              if (borrows.incrementAndGet() != 1) throw new SQLException("pool of one exhausted");
              return connection;
            });
    when(connection.getAutoCommit()).thenReturn(true);
    when(connection.prepareStatement(anyString())).thenReturn(marker);
    when(marker.getConnection()).thenReturn(connection);
    when(marker.executeUpdate())
        .thenAnswer(
            call -> {
              order.add("marker");
              return 1;
            });
    doAnswer(
            call -> {
              order.add("commit");
              return null;
            })
        .when(connection)
        .commit();
    doAnswer(
            call -> {
              order.add("rollback");
              return null;
            })
        .when(connection)
        .rollback();
    context = new AnnotationConfigApplicationContext();
    context.registerBean(DataSource.class, () -> pool);
    context.register(Config.class);
    context.refresh();
    tx = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
    TenantContext.set(tenant, null);
  }

  @AfterEach
  void cleanup() {
    TenantContext.clear();
    context.close();
  }

  @Test
  void eventCaptureUsesTheStockConnectionAndWritesBeforeCommit() throws Exception {
    tx.executeWithoutResult(status -> context.publishEvent(change));
    assertThat(borrows).hasValue(1);
    assertThat(order).containsExactly("marker", "commit");
    verify(connection).close();
    verify(marker).executeUpdate();
    assertThat(TenantContext.tenantId()).isEqualTo(tenant);
  }

  @Test
  void rolledBackStockDoesNotCaptureAPendingMarker() throws Exception {
    tx.executeWithoutResult(
        status -> {
          context.publishEvent(change);
          status.setRollbackOnly();
        });
    assertThat(borrows).hasValue(1);
    assertThat(order).containsExactly("rollback");
    verify(marker, never()).executeUpdate();
  }

  @Test
  void captureFailureRollsBackStockInsteadOfLosingItsMarker() throws Exception {
    doThrow(new SQLException("marker write failed", "40001")).when(marker).executeUpdate();
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> context.publishEvent(change)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(borrows).hasValue(1);
    assertThat(order).containsExactly("rollback");
    verify(connection, never()).commit();
  }

  @Test
  void captureRequiresAnExistingTransactionAndMatchingTenant() {
    StockPushService service = context.getBean(StockPushService.class);
    assertThatThrownBy(() -> service.capture(change))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(borrows).hasValue(0);
    StockChanged other = new StockChanged(UUID.randomUUID(), change.componentSkuIds(), Set.of());
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.capture(other)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tenant");
    assertThat(order).containsExactly("rollback");
  }

  @Configuration
  @EnableTransactionManagement
  static class Config {
    @Bean
    DataSourceTransactionManager transactions(DataSource pool) {
      return new DataSourceTransactionManager(pool);
    }

    @Bean
    StockPushService service(DataSource pool, DataSourceTransactionManager transactions) {
      return new StockPushService(
          new JdbcTemplate(pool),
          mock(StockAvailability.class),
          mock(OutboxAppender.class),
          new StockPushProperties(),
          new SimpleMeterRegistry(),
          JsonMapper.builder().build(),
          transactions);
    }

    @Bean
    StockPushListener listener(StockPushService service) {
      return new StockPushListener(service);
    }
  }
}
