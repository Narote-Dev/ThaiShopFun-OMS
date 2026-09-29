package com.thaishopfun.oms.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/** {@code GET /api/v1/me}. Shape is {@code tenant}, {@code role}, {@code entitlement}. */
public record MeResponse(Tenant tenant, String role, Entitlement entitlement) {

  public record Tenant(
      UUID id,
      String name,
      @JsonProperty("tsf_shop_id") String tsfShopId,
      @JsonProperty("membership_tier") String membershipTier) {}

  public record Entitlement(
      String status,
      @JsonProperty("expires_at") Instant expiresAt,
      @JsonProperty("ent_ver") long entVer) {}
}
