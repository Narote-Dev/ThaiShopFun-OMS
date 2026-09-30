package com.thaishopfun.oms.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A {@code sales_order} row. No PII: the recipient lives in {@code order_recipient}. */
public record SalesOrder(
    UUID id,
    UUID tenantId,
    UUID channelAccountId,
    String externalOrderId,
    String orderStatus,
    String paymentStatus,
    String fulfillmentStatus,
    String holdReason,
    String channelStatus,
    String paymentMethod,
    String currency,
    BigDecimal subtotal,
    BigDecimal shippingFee,
    BigDecimal discount,
    BigDecimal grandTotal,
    Instant orderedAt,
    Instant paidAt,
    Instant shipBy,
    Long externalVersion,
    long version) {}
