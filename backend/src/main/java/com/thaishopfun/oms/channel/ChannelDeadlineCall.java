package com.thaishopfun.oms.channel;

import java.time.Instant;

@FunctionalInterface
public interface ChannelDeadlineCall<T> {
  T call(Instant deadline) throws Exception;
}
