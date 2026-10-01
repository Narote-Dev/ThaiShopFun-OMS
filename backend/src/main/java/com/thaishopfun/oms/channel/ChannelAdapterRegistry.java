package com.thaishopfun.oms.channel;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ChannelAdapterRegistry {

  private final Map<Channel, ChannelAdapter> adapters;

  public ChannelAdapterRegistry(List<ChannelAdapter> adapters) {
    Map<Channel, ChannelAdapter> map = new EnumMap<>(Channel.class);
    for (ChannelAdapter adapter : adapters) {
      ChannelAdapter previous = map.put(adapter.channel(), adapter);
      if (previous != null) {
        throw new IllegalStateException("Duplicate ChannelAdapter for " + adapter.channel());
      }
    }
    this.adapters = Map.copyOf(map);
  }

  public ChannelAdapter require(Channel channel) {
    ChannelAdapter adapter = adapters.get(channel);
    if (adapter == null) {
      throw new IllegalStateException("No ChannelAdapter registered for " + channel);
    }
    return adapter;
  }

  public ChannelAdapter optional(Channel channel) {
    return adapters.get(channel);
  }
}
