package com.thaishopfun.oms.order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Controllable clock for hold-resolver backoff acceptance tests. */
public class MutableClock extends Clock {

  private final Clock base;
  private volatile Duration offset = Duration.ZERO;

  public MutableClock(Instant start) {
    this.base = Clock.fixed(start, ZoneOffset.UTC);
  }

  public MutableClock() {
    this.base = Clock.systemUTC();
  }

  public void advance(Duration duration) {
    offset = offset.plus(duration);
  }

  public void resetOffset() {
    offset = Duration.ZERO;
  }

  @Override
  public Instant instant() {
    return base.instant().plus(offset);
  }

  @Override
  public ZoneId getZone() {
    return base.getZone();
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return base.withZone(zone);
  }
}
