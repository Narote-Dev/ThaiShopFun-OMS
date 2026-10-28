package com.thaishopfun.oms.stockdrift;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.thaishopfun.oms.channel.*;
import com.thaishopfun.oms.channel.api.ListingPage;
import com.thaishopfun.oms.stockpush.StockPushService;
import com.thaishopfun.oms.tenant.TenantAwareDataSourceTransactionManager;
import com.thaishopfun.oms.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Exercises the production RLS transaction manager; only JDBC operations are mocked. */
class StockDriftTenantContextRegressionTest {
  @AfterEach
  void cleanup() {
    TenantContext.clear();
  }

  @ParameterizedTest
  @CsvSource({"true,true", "true,false", "false,true", "false,false"})
  @SuppressWarnings("unchecked")
  void adapterContextChangesCannotChangeReconcileRlsTenantOrAuditActor(
      boolean clearContext, boolean userRun) throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID actor = userRun ? UUID.randomUUID() : null;
    TenantContext.set(tenant, actor);
    var jdbc = mock(JdbcTemplate.class);
    var adapter = mock(ChannelAdapter.class);
    when(adapter.channel()).thenReturn(Channel.TSF);
    when(jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class))
        .thenReturn(OffsetDateTime.now());
    when(jdbc.query(contains("SELECT id, external_shop_id"), any(RowMapper.class)))
        .thenReturn(List.of(new StockDriftService.Account(UUID.randomUUID(), "shop")));

    // Capture the actual app.tenant_id value bound by the production transaction manager.
    var databaseTenant = new AtomicReference<String>();
    DataSource pool = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement binding = mock(PreparedStatement.class);
    ResultSet bound = mock(ResultSet.class);
    when(pool.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    when(connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)"))
        .thenReturn(binding);
    when(binding.executeQuery()).thenReturn(bound);
    when(bound.next()).thenReturn(true);
    doAnswer(
            call -> {
              databaseTenant.set(call.getArgument(1));
              return null;
            })
        .when(binding)
        .setString(eq(1), anyString());
    doAnswer(
            call -> {
              databaseTenant.set(null); // transaction-local GUC does not survive commit
              return null;
            })
        .when(connection)
        .commit();

    doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TenantContext.tenantId()).isEqualTo(tenant);
              assertThat(TenantContext.userId()).isEqualTo(actor);
              // Mirrors the fixture scope in the failing CI test, or a scope that leaks another
              // tenant.
              if (clearContext) TenantContext.clear();
              else TenantContext.set(UUID.randomUUID(), UUID.randomUUID());
              return new ListingPage(List.of(), call.getArgument(1) == null ? "next" : null);
            })
        .when(adapter)
        .listListings(any(), any());
    when(jdbc.update(contains("INSERT INTO audit_log"), any(Object[].class)))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(databaseTenant.get()).isEqualTo(tenant.toString());
              assertThat(TenantContext.tenantId()).isEqualTo(tenant);
              assertThat(TenantContext.userId()).isEqualTo(actor);
              assertThat(call.<UUID>getArgument(2)).isEqualTo(tenant);
              assertThat(call.<String>getArgument(3)).isEqualTo(userRun ? "USER" : "SYSTEM");
              assertThat(call.<String>getArgument(4))
                  .isEqualTo(actor == null ? null : actor.toString());
              return 1;
            });

    var service =
        new StockDriftService(
            jdbc,
            new ChannelAdapterRegistry(List.of(adapter)),
            mock(StockPushService.class),
            new JsonMapper(),
            new TenantAwareDataSourceTransactionManager(pool));
    assertThat(service.run(tenant).drifting()).isZero();
    verify(adapter, times(2)).listListings(any(), any());
    verify(binding, times(3)).setString(1, tenant.toString());
    verify(jdbc).update(contains("INSERT INTO audit_log"), any(Object[].class));
    assertThat(databaseTenant.get()).isNull();
    assertThat(TenantContext.tenantId()).isEqualTo(tenant);
    assertThat(TenantContext.userId()).isEqualTo(actor);
  }
}
