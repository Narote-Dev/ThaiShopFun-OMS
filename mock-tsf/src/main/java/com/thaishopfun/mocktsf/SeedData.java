package com.thaishopfun.mocktsf;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Sellers the picker and {@code login_hint} can issue. EXPIRED is not a membership status OMS
 * accepts, so the expired shop is {@code ACTIVE} with {@code expires_at} in the past.
 */
@Component
public class SeedData {

  public record ShopUser(
      String userId,
      String email,
      String shopId,
      String shopName,
      String role,
      String tier,
      String status,
      Instant expiresAt,
      long entVer) {

    public List<String> entitlements() {
      return List.of("oms");
    }
  }

  private final List<ShopUser> users = new ArrayList<>();

  public SeedData() {
    Instant now = Instant.now();
    Instant future = now.plus(365, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    Instant grace = now.plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    Instant past = now.minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    users.add(user("owner-active", "shop_active", "Active Shop", "ACTIVE", future));
    users.add(user("owner-grace", "shop_grace", "Grace Shop", "GRACE", grace));
    users.add(user("owner-suspended", "shop_suspended", "Suspended Shop", "SUSPENDED", future));
    users.add(user("owner-expired", "shop_expired", "Expired Shop", "ACTIVE", past));
  }

  public List<ShopUser> users() {
    synchronized (users) {
      return List.copyOf(users);
    }
  }

  public Optional<ShopUser> find(String loginHint) {
    if (loginHint == null || loginHint.isBlank()) {
      return Optional.empty();
    }
    String hint = loginHint.trim();
    synchronized (users) {
      for (ShopUser user : users) {
        if (user.userId().equals(hint) || user.shopId().equals(hint)) {
          return Optional.of(user);
        }
      }
    }
    return Optional.empty();
  }

  /** Token-only overrides. {@code null} keeps the seed value. Does not change the stored shop. */
  public ShopUser override(ShopUser user, Long entVer, String status, Instant expiresAt) {
    return new ShopUser(
        user.userId(),
        user.email(),
        user.shopId(),
        user.shopName(),
        user.role(),
        user.tier(),
        status == null ? user.status() : status,
        expiresAt == null ? user.expiresAt() : expiresAt,
        entVer == null ? user.entVer() : entVer);
  }

  /**
   * A membership event OMS accepted (2xx). A lower {@code ent_ver} does not move the seed
   * backwards. Unknown shops stay unknown.
   */
  public void noteAccepted(JsonNode event) {
    if (event == null || !"membership.changed".equals(event.path("event_type").asString())) {
      return;
    }
    JsonNode data = event.get("data");
    if (data == null || !data.isObject() || !data.path("ent_ver").isIntegralNumber()) {
      return;
    }
    long entVer = data.path("ent_ver").asLong();
    String shopId = event.path("tsf_shop_id").asString();
    String status = data.path("status").asString(null);
    String tier = data.path("tier").asString(null);
    String name = data.path("name").asString(null);
    Instant expiresAt = null;
    if (data.hasNonNull("expires_at")) {
      try {
        expiresAt = Instant.parse(data.get("expires_at").asString());
      } catch (RuntimeException ex) {
        expiresAt = null;
      }
    }
    // Step 1: Keep the higher entitlement version. A stale event must not mint a stale token.
    synchronized (users) {
      for (int i = 0; i < users.size(); i++) {
        ShopUser current = users.get(i);
        if (!current.shopId().equals(shopId) || entVer < current.entVer()) {
          continue;
        }
        users.set(
            i,
            new ShopUser(
                current.userId(),
                current.email(),
                current.shopId(),
                name == null || name.isBlank() ? current.shopName() : name,
                current.role(),
                tier == null || tier.isBlank() ? current.tier() : tier,
                status == null || status.isBlank() ? current.status() : status,
                expiresAt == null ? current.expiresAt() : expiresAt,
                entVer));
        return;
      }
    }
  }

  private static ShopUser user(
      String userId, String shopId, String shopName, String status, Instant expiresAt) {
    return new ShopUser(
        userId, userId + "@shop.example", shopId, shopName, "OWNER", "PRO", status, expiresAt, 1);
  }
}
