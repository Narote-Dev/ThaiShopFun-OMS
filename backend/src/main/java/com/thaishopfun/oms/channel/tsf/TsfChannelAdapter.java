package com.thaishopfun.oms.channel.tsf;

import com.thaishopfun.oms.channel.AccountResilienceRegistry;
import com.thaishopfun.oms.channel.BaseChannelAdapter;
import com.thaishopfun.oms.channel.Channel;
import com.thaishopfun.oms.channel.ChannelAccountRef;
import com.thaishopfun.oms.channel.ChannelCapabilities;
import com.thaishopfun.oms.channel.ChannelMetrics;
import com.thaishopfun.oms.channel.ChannelProperties;
import com.thaishopfun.oms.channel.Sleeper;
import com.thaishopfun.oms.channel.api.CancelRequest;
import com.thaishopfun.oms.channel.api.CancelResponse;
import com.thaishopfun.oms.channel.api.LabelContent;
import com.thaishopfun.oms.channel.api.ListingPage;
import com.thaishopfun.oms.channel.api.OrderDetail;
import com.thaishopfun.oms.channel.api.OrderPage;
import com.thaishopfun.oms.channel.api.PaymentStatus;
import com.thaishopfun.oms.channel.api.Shipment;
import com.thaishopfun.oms.channel.api.ShipmentRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class TsfChannelAdapter extends BaseChannelAdapter {

  private static final ChannelCapabilities CAPABILITIES =
      new ChannelCapabilities(true, true, true, true, true, true, false, true);

  private final TsfHttpTransport transport;
  private final JsonMapper json;

  public TsfChannelAdapter(
      AccountResilienceRegistry resilience,
      ChannelProperties properties,
      ChannelMetrics metrics,
      Sleeper sleeper,
      Clock clock,
      TsfHttpTransport transport,
      JsonMapper json) {
    super(resilience, properties, metrics, sleeper, clock);
    this.transport = transport;
    this.json = json;
  }

  @Override
  public Channel channel() {
    return Channel.TSF;
  }

  @Override
  public ChannelCapabilities capabilities() {
    return CAPABILITIES;
  }

  @Override
  protected OrderPage doListOrders(
      ChannelAccountRef account, Instant updatedSince, String cursor, int limit, Instant deadline) {
    StringBuilder path =
        new StringBuilder("/shops/")
            .append(encodePathSegment(account.externalShopId()))
            .append("/orders?limit=")
            .append(limit);
    if (updatedSince != null) {
      path.append("&updated_since=").append(encodeQueryParam(updatedSince.toString()));
    }
    if (cursor != null && !cursor.isBlank()) {
      path.append("&cursor=").append(encodeQueryParam(cursor));
    }
    TsfHttpTransport.HttpResult result = transport.get(path.toString(), deadline);
    return json.readValue(result.body(), OrderPage.class);
  }

  @Override
  protected OrderDetail doGetOrder(
      ChannelAccountRef account, String externalOrderId, Instant deadline) {
    TsfHttpTransport.HttpResult result =
        transport.get("/orders/" + encodePathSegment(externalOrderId), deadline);
    return json.readValue(result.body(), OrderDetail.class);
  }

  @Override
  protected PaymentStatus doGetPaymentStatus(
      ChannelAccountRef account, String externalOrderId, Instant deadline) {
    TsfHttpTransport.HttpResult result =
        transport.get(
            "/orders/" + encodePathSegment(externalOrderId) + "/payment-status", deadline);
    return json.readValue(result.body(), PaymentStatus.class);
  }

  @Override
  protected ListingPage doListListings(ChannelAccountRef account, String cursor, Instant deadline) {
    StringBuilder path =
        new StringBuilder("/shops/")
            .append(encodePathSegment(account.externalShopId()))
            .append("/listings?limit=")
            .append(settings().getListingsPageLimit());
    if (cursor != null && !cursor.isBlank()) {
      path.append("&cursor=").append(encodeQueryParam(cursor));
    }
    TsfHttpTransport.HttpResult result = transport.get(path.toString(), deadline);
    return json.readValue(result.body(), ListingPage.class);
  }

  @Override
  protected Shipment doCreateShipment(
      ChannelAccountRef account,
      String externalOrderId,
      String idempotencyKey,
      ShipmentRequest request,
      Instant deadline) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("carrier", request.carrier());
    String jsonBody = json.writeValueAsString(body);
    TsfHttpTransport.HttpResult result =
        transport.post(
            "/orders/" + encodePathSegment(externalOrderId) + "/shipments",
            deadline,
            jsonBody,
            Map.of("Idempotency-Key", idempotencyKey));
    return json.readValue(result.body(), Shipment.class);
  }

  @Override
  protected LabelContent doGetLabel(
      ChannelAccountRef account, String shipmentId, Instant deadline) {
    TsfHttpTransport.HttpResult result =
        transport.get("/shipments/" + encodePathSegment(shipmentId) + "/label", deadline);
    return new LabelContent(result.body());
  }

  @Override
  protected CancelResponse doRequestCancel(
      ChannelAccountRef account,
      String externalOrderId,
      String idempotencyKey,
      CancelRequest request,
      Instant deadline) {
    String jsonBody = json.writeValueAsString(request);
    TsfHttpTransport.HttpResult result =
        transport.post(
            "/orders/" + encodePathSegment(externalOrderId) + "/cancel-requests",
            deadline,
            jsonBody,
            Map.of("Idempotency-Key", idempotencyKey));
    return json.readValue(result.body(), CancelResponse.class);
  }

  private static String encodePathSegment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String encodeQueryParam(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
