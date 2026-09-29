package com.thaishopfun.mocktsf;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

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

  private final List<ShopUser> users;

  public SeedData() {
    Instant now = Instant.now();
    Instant future = now.plus(365, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    Instant grace = now.plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    Instant past = now.minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    this.users =
        List.of(
            user("owner-active", "shop_active", "Active Shop", "ACTIVE", future),
            user("owner-grace", "shop_grace", "Grace Shop", "GRACE", grace),
            user("owner-suspended", "shop_suspended", "Suspended Shop", "SUSPENDED", future),
            user("owner-expired", "shop_expired", "Expired Shop", "ACTIVE", past));
  }

  public List<ShopUser> users() {
    return users;
  }

  public Optional<ShopUser> find(String loginHint) {
    if (loginHint == null || loginHint.isBlank()) {
      return Optional.empty();
    }
    String hint = loginHint.trim();
    for (ShopUser user : users) {
      if (user.userId().equals(hint) || user.shopId().equals(hint)) {
        return Optional.of(user);
      }
    }
    return Optional.empty();
  }

  private static ShopUser user(
      String userId, String shopId, String shopName, String status, Instant expiresAt) {
    return new ShopUser(
        userId, userId + "@shop.example", shopId, shopName, "OWNER", "PRO", status, expiresAt, 1);
  }
}
