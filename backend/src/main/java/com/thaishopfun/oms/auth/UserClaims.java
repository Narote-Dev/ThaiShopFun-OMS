package com.thaishopfun.oms.auth;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Claim mapping for a user access token (plan section 4.1).
 *
 * <ul>
 *   <li>{@code sub} → {@code app_user.tsf_user_id}
 *   <li>{@code email} → {@code app_user.email} (optional; never logged or written to audit)
 *   <li>{@code name}, else {@code display_name} → {@code app_user.display_name} (optional)
 *   <li>{@code tsf_shop_id} → {@code tenant.tsf_shop_id}
 *   <li>{@code shop_name} → {@code tenant.name}, or {@code tsf_shop_id} when the claim is absent
 *   <li>{@code shop_role} → {@code tenant_membership.role} ({@code OWNER}, {@code ADMIN}, {@code
 *       STAFF})
 *   <li>{@code membership.tier} → {@code tenant.membership_tier}
 *   <li>{@code membership.status} → {@code tenant.entitlement_status} ({@code ACTIVE}, {@code
 *       GRACE}, {@code SUSPENDED})
 *   <li>{@code membership.expires_at} → {@code tenant.entitlement_expires_at} (ISO-8601, optional)
 *   <li>{@code ent_ver} → {@code tenant.ent_ver}
 *   <li>{@code entitlements} must contain {@code oms} or the gate returns {@code
 *       ENTITLEMENT_INACTIVE}
 * </ul>
 *
 * One token is one shop. Service tokens ({@code aud=oms-internal}) are not parsed here.
 */
public record UserClaims(
    String tsfUserId,
    String email,
    String displayName,
    String shopId,
    String shopName,
    String role,
    String tier,
    String status,
    Instant expiresAt,
    long entVer,
    List<String> entitlements) {

  private static final int MAX_TEXT = 200;
  private static final Set<String> ROLES = Set.of("OWNER", "ADMIN", "STAFF");
  private static final Set<String> STATUSES = Set.of("ACTIVE", "GRACE", "SUSPENDED");

  static UserClaims from(Jwt jwt) {
    // Step 1: Identity and shop. A service token never reaches this parser.
    String tsfUserId = requiredText(jwt.getSubject(), "sub");
    String shopId = requiredText(jwt.getClaimAsString("tsf_shop_id"), "tsf_shop_id");
    String role = requiredText(jwt.getClaimAsString("shop_role"), "shop_role");
    if (!ROLES.contains(role)) {
      throw new InvalidAccessTokenException("shop_role is invalid");
    }

    // Step 2: Membership object from section 4.1.
    Object rawMembership = jwt.getClaim("membership");
    if (!(rawMembership instanceof Map<?, ?> membership)) {
      throw new InvalidAccessTokenException("membership claim is required");
    }
    String tier = requiredText(asText(membership.get("tier")), "membership.tier");
    String status = requiredText(asText(membership.get("status")), "membership.status");
    if (!STATUSES.contains(status)) {
      throw new InvalidAccessTokenException("membership.status is invalid");
    }
    Instant expiresAt = parseExpiresAt(membership.get("expires_at"));
    long entVer = parseEntVer(jwt.getClaim("ent_ver"));

    // Step 3: Optional profile fields. Missing entitlements fail the gate later, not here.
    String email = optionalText(jwt.getClaimAsString("email"));
    String displayName =
        optionalText(firstText(jwt.getClaimAsString("name"), jwt.getClaimAsString("display_name")));
    String shopName = optionalText(jwt.getClaimAsString("shop_name"));
    if (shopName == null) {
      shopName = shopId;
    }
    List<String> entitlements = readEntitlements(jwt.getClaim("entitlements"));
    return new UserClaims(
        tsfUserId,
        email,
        displayName,
        shopId,
        shopName,
        role,
        tier,
        status,
        expiresAt,
        entVer,
        entitlements);
  }

  private static List<String> readEntitlements(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> values)) {
      throw new InvalidAccessTokenException("entitlements claim is invalid");
    }
    for (Object value : values) {
      if (!(value instanceof String)) {
        throw new InvalidAccessTokenException("entitlements claim is invalid");
      }
    }
    @SuppressWarnings("unchecked")
    List<String> typed = (List<String>) values;
    return List.copyOf(typed);
  }

  private static Instant parseExpiresAt(Object raw) {
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof String text) || text.isBlank()) {
      throw new InvalidAccessTokenException("membership.expires_at is invalid");
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException ex) {
      throw new InvalidAccessTokenException("membership.expires_at is invalid");
    }
  }

  private static long parseEntVer(Object raw) {
    if (!(raw instanceof Number number)) {
      throw new InvalidAccessTokenException("ent_ver claim is required");
    }
    long entVer = number.longValue();
    if (entVer < 0) {
      throw new InvalidAccessTokenException("ent_ver claim is invalid");
    }
    return entVer;
  }

  private static String requiredText(String value, String claim) {
    String text = optionalText(value);
    if (text == null) {
      throw new InvalidAccessTokenException(claim + " claim is required");
    }
    return text;
  }

  private static String optionalText(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > MAX_TEXT || containsControl(trimmed)) {
      throw new InvalidAccessTokenException("claim text is invalid");
    }
    return trimmed;
  }

  private static boolean containsControl(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) < ' ') {
        return true;
      }
    }
    return false;
  }

  private static String asText(Object value) {
    return value instanceof String text ? text : null;
  }

  private static String firstText(String primary, String fallback) {
    String first = optionalText(primary);
    return first != null ? first : fallback;
  }
}
