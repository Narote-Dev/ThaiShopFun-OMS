package com.thaishopfun.oms.listing.intake;

import com.thaishopfun.oms.inbox.InboxHandler;
import com.thaishopfun.oms.inbox.InboxMessage;
import com.thaishopfun.oms.inbox.NonRetryableInboxException;
import com.thaishopfun.oms.listing.ChannelListingRepository;
import com.thaishopfun.oms.order.ChannelAccountLookup;
import com.thaishopfun.oms.order.ChannelAccountLookup.TsfAccount;
import com.thaishopfun.oms.order.intake.OrderIntakeSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnProperty(
    prefix = "oms.order-intake",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ListingChangedHandler implements InboxHandler {

  private final ChannelAccountLookup channels;
  private final ChannelListingRepository listings;

  public ListingChangedHandler(ChannelAccountLookup channels, ChannelListingRepository listings) {
    this.channels = channels;
    this.listings = listings;
  }

  @Override
  public String eventType() {
    return "listing.changed";
  }

  @Override
  public void handle(InboxMessage message) {
    TsfAccount account = requireTsfAccount(message);
    JsonNode data = message.payload().path("data");
    String listingSkuId = requiredText(data, "listing_sku_id");
    String action = requiredText(data, "action");
    if ("UPSERT".equals(action)) {
      listings.upsertFromChannel(
          message.tenantId(),
          account.id(),
          listingSkuId,
          optionalText(data, "seller_sku"),
          optionalText(data, "name"));
      return;
    }
    if ("DELETE".equals(action)) {
      listings.markRemoved(message.tenantId(), account.id(), listingSkuId);
      return;
    }
    throw new NonRetryableInboxException("unsupported listing.changed action: " + action);
  }

  private TsfAccount requireTsfAccount(InboxMessage message) {
    String shopId = text(message.payload(), "tsf_shop_id");
    return channels
        .tsfByExternalShopId(shopId)
        .orElseThrow(() -> new RuntimeException(OrderIntakeSupport.TSF_CHANNEL_ACCOUNT_MISSING));
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isString() || value.asString().isBlank()) {
      throw new NonRetryableInboxException(field + " is required");
    }
    return value.asString();
  }

  private static String optionalText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isString()) {
      return "";
    }
    return value.asString("");
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isString() || value.asString().isBlank()) {
      throw new NonRetryableInboxException("data." + field + " is required");
    }
    return value.asString();
  }
}
