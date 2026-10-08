package com.thaishopfun.oms.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.auth.UuidV7;
import com.thaishopfun.oms.invariant.SkipInvariantCheck;
import com.thaishopfun.oms.stock.StockFixture;
import com.thaishopfun.oms.stock.StockTestBase;
import com.thaishopfun.oms.tenant.TenantContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@SkipInvariantCheck("Direct handler and audit seeding")
class MembershipChangedHandlerAuditTest extends StockTestBase {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant EXPIRES =
      Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);

  @Autowired MembershipChangedHandler handler;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void jitProvisionThenInboxWritesOneAuditPerEntVerAndReplayIsIdempotent() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    long priorVer = 3;
    long nextVer = 4;
    UUID tenantId = shop.tenant();

    fixture.runInTenant(
        tenantId,
        () -> {
          jdbc.update(
              """
              UPDATE tenant
              SET membership_tier = 'PRO', entitlement_status = 'ACTIVE',
                  entitlement_expires_at = ?, ent_ver = ?
              WHERE id = ?
              """,
              OffsetDateTime.ofInstant(EXPIRES, ZoneOffset.UTC),
              priorVer,
              tenantId);
          jdbc.update(
              """
              INSERT INTO audit_log (
                id, tenant_id, actor_type, action, entity_type, entity_id, "before", "after"
              ) VALUES (?, ?, 'TSF', 'membership.changed', 'tenant', ?, ?::jsonb, ?::jsonb)
              """,
              UuidV7.generate(),
              tenantId,
              tenantId.toString(),
              "{\"entitlement_status\":\"ACTIVE\",\"ent_ver\":2}",
              "{\"entitlement_status\":\"ACTIVE\",\"ent_ver\":" + priorVer + "}");
        });

    // Login JIT applied ent_ver N+1 without a membership.changed audit for that version.
    try (var app = AuthTestSupport.app();
        var ps =
            app.prepareStatement(
                """
                SELECT provision_tenant(?, ?, ?, ?, ?, ?)
                """)) {
      ps.setString(1, fixture.tsfShopId(shop));
      ps.setString(2, "Shop");
      ps.setString(3, "PRO");
      ps.setString(4, "ACTIVE");
      ps.setObject(5, OffsetDateTime.ofInstant(EXPIRES, ZoneOffset.UTC));
      ps.setLong(6, nextVer);
      ps.executeQuery();
    }

    ObjectNode payload = membershipPayload(nextVer, EXPIRES.toString());
    deliver(handler, tenantId, payload, "evt-" + UUID.randomUUID());
    assertAuditCount(tenantId, nextVer, 1);

    deliver(handler, tenantId, payload, "evt-replay-" + UUID.randomUUID());
    assertAuditCount(tenantId, nextVer, 1);
    assertAuditCount(tenantId, priorVer, 1);
  }

  @Test
  void nanosecondExpiresAtMatchesDbMicrosAndReplayWritesOneAudit() throws Exception {
    StockFixture.Shop shop = fixture.shop("ACTIVE");
    long entVer = 7;
    UUID tenantId = shop.tenant();
    String payloadExpiry = EXPIRES.toString() + "123";

    fixture.runInTenant(
        tenantId,
        () ->
            jdbc.update(
                """
                UPDATE tenant
                SET membership_tier = 'PRO', entitlement_status = 'ACTIVE',
                    entitlement_expires_at = ?, ent_ver = ?
                WHERE id = ?
                """,
                OffsetDateTime.ofInstant(EXPIRES, ZoneOffset.UTC),
                entVer,
                tenantId));

    ObjectNode payload = membershipPayload(entVer, payloadExpiry);
    deliver(handler, tenantId, payload, "evt-nano-1");
    assertAuditCount(tenantId, entVer, 1);
    deliver(handler, tenantId, payload, "evt-nano-2");
    assertAuditCount(tenantId, entVer, 1);
  }

  private void deliver(
      MembershipChangedHandler handler, UUID tenantId, ObjectNode root, String eventId) {
    ObjectNode envelope = JSON.createObjectNode();
    envelope.put("event_id", eventId);
    envelope.put("event_type", "membership.changed");
    envelope.set("data", root);
    InboxMessage message =
        new InboxMessage(
            UUID.randomUUID(),
            tenantId,
            "TSF",
            eventId,
            "membership.changed",
            tenantId.toString(),
            1,
            false,
            envelope);
    fixture.runInTenant(tenantId, () -> handler.handle(message));
  }

  private static ObjectNode membershipPayload(long entVer, String expiresAt) {
    ObjectNode data = JSON.createObjectNode();
    data.put("tier", "PRO");
    data.put("status", "ACTIVE");
    data.put("ent_ver", entVer);
    data.put("expires_at", expiresAt);
    return data;
  }

  private void assertAuditCount(UUID tenantId, long entVer, int expected) {
    fixture.runInTenant(
        tenantId,
        () -> {
          Long count =
              jdbc.queryForObject(
                  """
                  SELECT count(*) FROM audit_log
                  WHERE tenant_id = ?
                    AND action = 'membership.changed'
                    AND ("after"->>'ent_ver')::bigint = ?
                  """,
                  Long.class,
                  tenantId,
                  entVer);
          assertThat(count).isEqualTo((long) expected);
        });
  }
}
