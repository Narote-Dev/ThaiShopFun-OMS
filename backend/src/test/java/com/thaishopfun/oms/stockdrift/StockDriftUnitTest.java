package com.thaishopfun.oms.stockdrift;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.thaishopfun.oms.channel.*;
import com.thaishopfun.oms.channel.api.ListingPage;
import com.thaishopfun.oms.listing.ChannelListingAccess;
import com.thaishopfun.oms.stockpush.StockPushService;
import com.thaishopfun.oms.tenant.TenantContext;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

class StockDriftUnitTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final ChannelAdapter adapter = mock(ChannelAdapter.class);
  private final StockPushService pushes = mock(StockPushService.class);
  private final UUID tenant = UUID.randomUUID();
  private final UUID account = UUID.randomUUID();
  private final StockDriftService service;

  @SuppressWarnings("unchecked")
  StockDriftUnitTest() {
    when(adapter.channel()).thenReturn(Channel.TSF);
    service =
        new StockDriftService(
            jdbc,
            new ChannelAdapterRegistry(List.of(adapter)),
            pushes,
            new JsonMapper(),
            new Transactions());
    when(jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class))
        .thenReturn(OffsetDateTime.now());
    when(jdbc.query(contains("SELECT id, external_shop_id"), any(RowMapper.class)))
        .thenReturn(List.of(new StockDriftService.Account(account, "shop")));
  }

  @AfterEach
  void cleanup() {
    TenantContext.clear();
  }

  @Test
  void pagesWithoutOpenTransactionAndAbortsAllWritesOnFetchFailureRestoringContext() {
    UUID previous = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    TenantContext.set(previous, user);
    when(adapter.listListings(any(), isNull()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TenantContext.requireTenantId()).isEqualTo(tenant);
              assertThat(TenantContext.userId()).isNull();
              assertThat(call.<ChannelAccountRef>getArgument(0).channelAccountId())
                  .isEqualTo(account);
              return new ListingPage(
                  List.of(new ListingPage.Listing("L", "S", "สินค้า", 2, 1)), "page2");
            });
    doThrow(new IllegalStateException("channel failed"))
        .when(adapter)
        .listListings(any(), eq("page2"));
    assertThatThrownBy(() -> service.run(tenant)).hasMessage("channel failed");
    assertThat(TenantContext.tenantId()).isEqualTo(previous);
    assertThat(TenantContext.userId()).isEqualTo(user);
    verify(jdbc, never()).update(anyString(), any(Object[].class));
    verifyNoInteractions(pushes);
  }

  @Test
  void repeatedCursorAndDuplicateListingsAbortInsteadOfResolvingAnIncompleteScan() {
    when(adapter.listListings(any(), any())).thenReturn(new ListingPage(List.of(), "loop"));
    assertThatThrownBy(() -> service.run(tenant)).hasMessage("Repeated listing cursor");
    when(adapter.listListings(any(), any()))
        .thenReturn(
            new ListingPage(
                List.of(
                    new ListingPage.Listing("L", "S", "สินค้า", 2, 1),
                    new ListingPage.Listing("L", "S", "สินค้า", 3, 2)),
                null));
    assertThatThrownBy(() -> service.run(tenant)).hasMessage("Invalid listing page");
    assertThat(TenantContext.tenantId()).isNull();
    verifyNoInteractions(pushes);
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void adminGatePrecedesManualRunAndStatusFilterIsValidated() {
    var access = mock(ChannelListingAccess.class);
    var worker = mock(StockDriftService.class);
    var controller = new StockDriftController(worker, access);
    doThrow(new IllegalStateException("forbidden")).when(access).requireWriter();
    assertThatThrownBy(controller::run).hasMessage("forbidden");
    assertThatThrownBy(() -> controller.issues("OTHER"))
        .isInstanceOf(com.thaishopfun.oms.listing.ListingApiException.class);
    verifyNoInteractions(worker);
  }

  @Test
  void disabledScheduleDoesNotDiscoverAccounts() {
    new StockDriftJob(jdbc, service, false).tick();
    verify(jdbc, never()).query(contains("list_stock_push_accounts"), any(RowMapper.class));
  }

  private static final class Transactions extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
