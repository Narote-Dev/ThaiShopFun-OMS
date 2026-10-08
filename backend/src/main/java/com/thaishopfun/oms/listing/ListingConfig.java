package com.thaishopfun.oms.listing;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ListingSyncProperties.class)
class ListingConfig {}
