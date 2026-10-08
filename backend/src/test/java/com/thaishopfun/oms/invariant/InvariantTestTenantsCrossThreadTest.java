package com.thaishopfun.oms.invariant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InvariantTestTenantsCrossThreadTest {

  @AfterEach
  void cleanup() {
    InvariantTestTenants.clear();
  }

  @Test
  void registerOnWorkerThreadIsVisibleToDrain() throws Exception {
    UUID tenantId = UUID.randomUUID();
    Thread worker =
        new Thread(() -> InvariantTestTenants.register(tenantId), "invariant-tenant-register");
    worker.start();
    worker.join();
    assertThat(InvariantTestTenants.drain()).containsExactly(tenantId);
  }
}
