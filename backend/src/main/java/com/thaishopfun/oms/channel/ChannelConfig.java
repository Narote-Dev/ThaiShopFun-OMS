package com.thaishopfun.oms.channel;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ChannelProperties.class, TsfProperties.class})
public class ChannelConfig {}
