package com.thaishopfun.oms.inbox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class InboxHandlerRegistry {

  private final Map<String, InboxHandler> handlers;

  public InboxHandlerRegistry(List<InboxHandler> found) {
    // Step 1: One handler per event type. A second bean for the same type fails startup.
    Map<String, InboxHandler> map = new LinkedHashMap<>();
    for (InboxHandler handler : found) {
      if (map.putIfAbsent(handler.eventType(), handler) != null) {
        throw new IllegalStateException("duplicate inbox handler for " + handler.eventType());
      }
    }
    this.handlers = Map.copyOf(map);
  }

  public InboxHandler find(String eventType) {
    return handlers.get(eventType);
  }
}
