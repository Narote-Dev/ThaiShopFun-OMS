package com.thaishopfun.oms.outbox;

import java.util.UUID;

/**
 * Called around the HTTP send. Production registers a no-op bean. Tests override it.
 *
 * <p>Package-private on purpose: this is not a public crash switch.
 */
class OutboxHooks {

  void beforeSend(UUID eventId) {}

  void afterAck(UUID eventId) {}
}
