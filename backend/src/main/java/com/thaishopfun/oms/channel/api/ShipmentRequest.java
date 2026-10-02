package com.thaishopfun.oms.channel.api;

/** Outbound shipment request. {@code partial} is an OMS-side flag; not all channels accept it. */
public record ShipmentRequest(String carrier, boolean partial) {

  public ShipmentRequest(String carrier) {
    this(carrier, false);
  }
}
