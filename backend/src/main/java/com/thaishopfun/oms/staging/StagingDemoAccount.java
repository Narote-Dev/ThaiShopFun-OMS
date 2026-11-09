package com.thaishopfun.oms.staging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
record StagingDemoAccount(
    String loginId,
    String tsfUserId,
    String email,
    String tsfShopId,
    String shopName,
    String shopRole,
    String tier,
    String status,
    long entVer,
    @JsonProperty("password_hash") String passwordHash) {}
