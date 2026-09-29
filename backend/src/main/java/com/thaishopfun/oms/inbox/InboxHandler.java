package com.thaishopfun.oms.inbox;

/** Handles one {@code event_type}. Writes must join the caller's transaction. */
public interface InboxHandler {

  String eventType();

  void handle(InboxMessage message);
}
