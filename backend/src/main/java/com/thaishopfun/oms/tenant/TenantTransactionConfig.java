package com.thaishopfun.oms.tenant;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class TenantTransactionConfig {

  @Bean
  public PlatformTransactionManager transactionManager(DataSource dataSource) {
    return new TenantAwareDataSourceTransactionManager(dataSource);
  }
}
