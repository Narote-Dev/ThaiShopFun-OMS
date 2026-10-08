package com.thaishopfun.oms.order.backfill;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OrderBackfillProperties.class)
public class OrderBackfillConfig {}
