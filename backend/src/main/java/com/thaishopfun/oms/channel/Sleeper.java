package com.thaishopfun.oms.channel;

import java.time.Duration;

/** Injectable sleep for retries and rate-limit backoff (tests use a no-op or controllable impl). */
public interface Sleeper {

  void sleep(Duration duration) throws InterruptedException;
}
