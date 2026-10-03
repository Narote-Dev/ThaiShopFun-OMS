package com.thaishopfun.oms.order.web;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
class OrderController {

  private final OrderQueryService queries;
  private final OrderCancelService cancel;

  OrderController(OrderQueryService queries, OrderCancelService cancel) {
    this.queries = queries;
    this.cancel = cancel;
  }

  @GetMapping
  ResponseEntity<OrderViews.Page<OrderViews.ListItem>> list(
      @RequestParam(name = "order_status", required = false) String orderStatus,
      @RequestParam(name = "payment_status", required = false) String paymentStatus,
      @RequestParam(name = "fulfillment_status", required = false) String fulfillmentStatus,
      @RequestParam(name = "hold_reason", required = false) String holdReason,
      @RequestParam(name = "channel", required = false) String channel,
      @RequestParam(name = "channel_account_id", required = false) UUID channelAccountId,
      @RequestParam(name = "ordered_from", required = false) String orderedFrom,
      @RequestParam(name = "ordered_to", required = false) String orderedTo,
      @RequestParam(name = "q", required = false) String q,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "cursor", required = false) String cursor) {
    return ok(
        queries.list(
            orderStatus,
            paymentStatus,
            fulfillmentStatus,
            holdReason,
            channel,
            channelAccountId,
            orderedFrom,
            orderedTo,
            q,
            limit,
            cursor));
  }

  @GetMapping("/holds")
  ResponseEntity<OrderViews.HoldsView> holds() {
    return ok(queries.holds());
  }

  @GetMapping("/{id}")
  ResponseEntity<OrderViews.DetailView> detail(@PathVariable UUID id) {
    return ok(queries.detail(id));
  }

  @PostMapping("/{id}/cancel-requests")
  ResponseEntity<OrderViews.CancelResponseView> cancelRequest(
      @PathVariable UUID id, @RequestBody(required = false) OrderViews.CancelRequestBody body) {
    OrderViews.CancelResponseView response = cancel.requestCancel(id, body);
    return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(response);
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
