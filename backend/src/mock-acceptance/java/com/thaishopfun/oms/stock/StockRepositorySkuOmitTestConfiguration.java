package com.thaishopfun.oms.stock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@TestConfiguration
public class StockRepositorySkuOmitTestConfiguration {

  @Bean
  @Primary
  StockRepository stockRepository(JdbcTemplate jdbc) {
    StockRepository delegate = new StockRepository(jdbc);
    StockRepository repository = spy(delegate);
    doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Map<UUID, StockRepository.SkuInfo> skus =
                  new LinkedHashMap<>(
                      (Map<UUID, StockRepository.SkuInfo>) invocation.callRealMethod());
              UUID omit = StockSkuLookupTestSupport.omittedSkuId();
              if (omit != null) {
                skus.remove(omit);
              }
              return skus;
            })
        .when(repository)
        .skus(any(Collection.class));
    return repository;
  }
}
