package com.thaishopfun.oms.channel;

/**
 * Feature flags for a {@link ChannelAdapter}. Callers and UI gate flows on these flags; unsupported
 * operations throw {@link com.thaishopfun.oms.channel.exception.UnsupportedCapabilityException}.
 */
public record ChannelCapabilities(
    boolean supportsWebhooks,
    boolean supportsOrderPull,
    boolean supportsStockPush,
    boolean supportsListingSync,
    boolean supportsCancelRequest,
    boolean supportsLabel,
    boolean supportsReturn,
    boolean supportsPartialShipment,
    boolean supportsCod) {}
