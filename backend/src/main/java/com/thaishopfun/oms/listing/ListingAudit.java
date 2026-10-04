package com.thaishopfun.oms.listing;

import com.thaishopfun.oms.auth.UuidV7;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ListingAudit {

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public ListingAudit(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public void mapped(ChannelListingAccess.Actor actor, UUID listingId, UUID skuId) {
    write(actor, "CHANNEL_LISTING_MAPPED", listingId, Map.of(), Map.of("sku_id", skuId.toString()));
  }

  public void unmapped(ChannelListingAccess.Actor actor, UUID listingId, UUID previousSkuId) {
    java.util.Map<String, Object> before = new java.util.LinkedHashMap<>();
    if (previousSkuId != null) {
      before.put("sku_id", previousSkuId.toString());
    }
    write(actor, "CHANNEL_LISTING_UNMAPPED", listingId, before, Map.of());
  }

  private void write(
      ChannelListingAccess.Actor actor,
      String action,
      UUID listingId,
      Map<String, ?> before,
      Map<String, ?> after) {
    jdbc.update(
        """
        INSERT INTO audit_log
          (id, tenant_id, actor_type, actor_id, action, entity_type, entity_id, "before", "after")
        VALUES (?, ?, 'USER', ?, ?, 'channel_listing', ?, ?::jsonb, ?::jsonb)
        """,
        UuidV7.generate(),
        actor.tenantId(),
        actor.userId().toString(),
        action,
        listingId.toString(),
        json.writeValueAsString(before),
        json.writeValueAsString(after));
  }
}
