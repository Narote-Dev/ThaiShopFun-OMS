package com.thaishopfun.mocktsf.events;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/** In-memory OMS → TSF events that passed HMAC and schema checks. Keeps the last 1000. */
@Component
public class ReceivedEventStore {

  static final int MAX_EVENTS = 1000;

  public record Received(String eventId, String eventType, String receivedAt, String body) {}

  private final CopyOnWriteArrayList<Received> events = new CopyOnWriteArrayList<>();

  public synchronized boolean add(Received event) {
    for (Received existing : events) {
      if (existing.eventId().equals(event.eventId())) {
        return false;
      }
    }
    events.add(event);
    // Step 1: Drop the oldest once the cap is passed. A dropped id can be stored again.
    while (events.size() > MAX_EVENTS) {
      events.remove(0);
    }
    return true;
  }

  public List<Received> all() {
    return List.copyOf(events);
  }

  public void clear() {
    events.clear();
  }

  public static String now() {
    return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
  }

  public List<java.util.Map<String, String>> view() {
    List<java.util.Map<String, String>> rows = new ArrayList<>();
    for (Received event : events) {
      java.util.Map<String, String> row = new java.util.LinkedHashMap<>();
      row.put("event_id", event.eventId());
      row.put("event_type", event.eventType());
      row.put("received_at", event.receivedAt());
      row.put("body", event.body());
      rows.add(row);
    }
    return rows;
  }
}
