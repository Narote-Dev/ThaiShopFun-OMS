package com.thaishopfun.oms.stockdrift;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.thaishopfun.oms.channel.ChannelAdapterRegistry;
import com.thaishopfun.oms.stockpush.StockPushService;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class StockDriftSkuCodesTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final JsonMapper json = new JsonMapper();

  @Test
  @SuppressWarnings("unchecked")
  void readsLegacyDetailsWithCurrentCodesInSkuOrderAndKeepsStoredIds() throws Exception {
    String mapped = UUID.randomUUID().toString();
    String component = UUID.randomUUID().toString();
    String missing = UUID.randomUUID().toString();
    String stored =
        json.writeValueAsString(
            Map.of(
                "run_id",
                UUID.randomUUID(),
                "listings",
                List.of(Map.of("sku_ids", List.of(mapped, component, missing)))));
    when(jdbc.query(
            contains("FROM reconciliation_issue"),
            any(RowMapper.class),
            nullable(String.class),
            nullable(String.class)))
        .thenAnswer(
            call -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString(1)).thenReturn(UUID.randomUUID().toString());
              when(rs.getString(2)).thenReturn("ACK");
              when(rs.getString(3)).thenReturn(stored);
              when(rs.getObject(4, OffsetDateTime.class)).thenReturn(OffsetDateTime.now());
              when(rs.getObject(5, OffsetDateTime.class)).thenReturn(OffsetDateTime.now());
              return List.of(call.<RowMapper<Map<String, Object>>>getArgument(1).mapRow(rs, 0));
            });
    when(jdbc.query(
            contains("FROM sku"), any(RowMapper.class), eq(mapped), eq(component), eq(missing)))
        .thenAnswer(
            call -> {
              RowMapper<?> mapper = call.getArgument(1);
              for (var entry : Map.of(component, "PART-B", mapped, "SELLER-A").entrySet()) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString(1)).thenReturn(entry.getKey());
                when(rs.getString(2)).thenReturn(entry.getValue());
                mapper.mapRow(rs, 0);
              }
              return List.of();
            });
    var issues = service().issues("ACK");
    JsonNode listing = ((JsonNode) issues.get(0).get("details")).path("listings").get(0);
    assertThat(listing.path("sku_codes")).isEqualTo(json.readTree("[\"SELLER-A\",\"PART-B\"]"));
    assertThat(listing.path("sku_ids"))
        .isEqualTo(json.readTree(stored).path("listings").get(0).path("sku_ids"));
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void emptyHistoryDoesNotQuerySkuTable() {
    when(jdbc.query(
            contains("FROM reconciliation_issue"), any(RowMapper.class), isNull(), isNull()))
        .thenReturn(List.of());
    assertThat(service().issues(null)).isEmpty();
    verify(jdbc, never()).query(contains("FROM sku"), any(RowMapper.class), any(Object[].class));
  }

  private StockDriftService service() {
    var transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    return new StockDriftService(
        jdbc, mock(ChannelAdapterRegistry.class), mock(StockPushService.class), json, transactions);
  }
}
