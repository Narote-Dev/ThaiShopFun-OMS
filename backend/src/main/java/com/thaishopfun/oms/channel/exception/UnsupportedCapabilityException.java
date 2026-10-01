package com.thaishopfun.oms.channel.exception;

import com.thaishopfun.oms.channel.Channel;

/** The adapter does not support the requested operation for this channel. */
public class UnsupportedCapabilityException extends RuntimeException {

  private final Channel channel;
  private final String operation;

  public UnsupportedCapabilityException(Channel channel, String operation) {
    super("Channel " + channel + " does not support " + operation);
    this.channel = channel;
    this.operation = operation;
  }

  public Channel channel() {
    return channel;
  }

  public String operation() {
    return operation;
  }
}
