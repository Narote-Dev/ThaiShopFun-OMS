package com.thaishopfun.oms.channel;

import com.thaishopfun.oms.channel.api.CancelRequest;
import com.thaishopfun.oms.channel.api.CancelResponse;
import com.thaishopfun.oms.channel.api.LabelContent;
import com.thaishopfun.oms.channel.api.ListingPage;
import com.thaishopfun.oms.channel.api.OrderDetail;
import com.thaishopfun.oms.channel.api.OrderPage;
import com.thaishopfun.oms.channel.api.PaymentStatus;
import com.thaishopfun.oms.channel.api.Shipment;
import com.thaishopfun.oms.channel.api.ShipmentRequest;
import java.time.Instant;

/**
 * Outbound integration for a sales channel (section 4.7 and future marketplaces).
 *
 * <p><b>Transactions:</b> Never hold a database transaction open across a call to this interface.
 * Load tenant context and any rows you need, commit or roll back, then invoke the adapter.
 *
 * <p><b>Capability mapping</b> (enforced in {@link BaseChannelAdapter}):
 *
 * <table>
 *   <tr><th>Method</th><th>Capability flag</th></tr>
 *   <tr><td>{@link #listOrders}</td><td>{@code supportsOrderPull}</td></tr>
 *   <tr><td>{@link #getOrder}</td><td>{@code supportsOrderPull}</td></tr>
 *   <tr><td>{@link #getPaymentStatus}</td><td>{@code supportsOrderPull}</td></tr>
 *   <tr><td>{@link #listListings}</td><td>{@code supportsOrderPull}</td></tr>
 *   <tr><td>{@link #createShipment}</td><td>{@code supportsLabel}</td></tr>
 *   <tr><td>{@link #getLabel}</td><td>{@code supportsLabel}</td></tr>
 *   <tr><td>{@link #requestCancel}</td><td>{@code supportsCancelRequest}</td></tr>
 * </table>
 *
 * Webhooks, stock push, returns, COD, and partial shipment are modeled on {@link
 * ChannelCapabilities} for gating elsewhere; partial shipment is checked on {@link #createShipment}
 * when {@link ShipmentRequest#partial()} is true.
 */
public interface ChannelAdapter {

  Channel channel();

  ChannelCapabilities capabilities();

  OrderPage listOrders(ChannelAccountRef account, Instant updatedSince, String cursor, int limit);

  OrderDetail getOrder(ChannelAccountRef account, String externalOrderId);

  PaymentStatus getPaymentStatus(ChannelAccountRef account, String externalOrderId);

  ListingPage listListings(ChannelAccountRef account, String cursor);

  Shipment createShipment(
      ChannelAccountRef account,
      String externalOrderId,
      String idempotencyKey,
      ShipmentRequest request);

  LabelContent getLabel(ChannelAccountRef account, String shipmentId);

  CancelResponse requestCancel(
      ChannelAccountRef account,
      String externalOrderId,
      String idempotencyKey,
      CancelRequest request);
}
