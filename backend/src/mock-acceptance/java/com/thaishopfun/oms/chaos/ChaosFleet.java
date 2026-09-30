package com.thaishopfun.oms.chaos;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A set of in-process "worker processes". Each one is a fresh instance on its own scheduler thread,
 * so stopping it and starting another is the in-process version of killing a container and starting
 * a new one. A tick that throws the death type marks the process dead: it stops polling and leaves
 * whatever it had claimed to the lease.
 */
final class ChaosFleet {

  private static final Duration TICK = Duration.ofMillis(5);
  private static final Duration STOP_LIMIT = Duration.ofSeconds(60);

  private final String name;
  private final int size;
  private final Supplier<Runnable> freshInstance;
  private final Class<? extends Throwable> death;
  private final List<Member> members = new ArrayList<>();
  private final List<String> errors = new CopyOnWriteArrayList<>();
  private final AtomicInteger deaths = new AtomicInteger();
  private int started;

  ChaosFleet(
      String name, int size, Supplier<Runnable> freshInstance, Class<? extends Throwable> death) {
    this.name = name;
    this.size = size;
    this.freshInstance = freshInstance;
    this.death = death;
  }

  synchronized void start() {
    for (int i = 0; i < size; i++) {
      members.add(launch());
    }
  }

  /** Replaces every dead process with a fresh instance. Returns how many were replaced. */
  synchronized int replaceDead() {
    int replaced = 0;
    for (int i = 0; i < members.size(); i++) {
      Member member = members.get(i);
      if (member.dead) {
        stop(member);
        members.set(i, launch());
        replaced++;
      }
    }
    return replaced;
  }

  /** Stops polling and waits for running ticks to finish. No thread is interrupted. */
  synchronized void stopAll() {
    for (Member member : members) {
      stop(member);
    }
    members.clear();
  }

  synchronized void restartAll() {
    stopAll();
    start();
  }

  /** Processes started so far, including the first set. */
  synchronized int started() {
    return started;
  }

  int deaths() {
    return deaths.get();
  }

  /** Errors that are not the planned death type. The suite expects none. */
  List<String> errors() {
    return new ArrayList<>(errors);
  }

  private Member launch() {
    started++;
    int number = started;
    Runnable tick = freshInstance.get();
    ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, name + "-" + number);
              thread.setDaemon(true);
              return thread;
            });
    Member member = new Member(scheduler);
    scheduler.scheduleWithFixedDelay(
        () -> {
          if (member.dead) {
            return;
          }
          try {
            tick.run();
          } catch (Throwable thrown) {
            // Step 1: The planned kill. Everything else is a real error and fails the suite.
            if (death.isInstance(thrown)) {
              member.dead = true;
              deaths.incrementAndGet();
            } else {
              errors.add(name + ": " + thrown);
            }
          }
        },
        0,
        TICK.toMillis(),
        TimeUnit.MILLISECONDS);
    return member;
  }

  private void stop(Member member) {
    member.scheduler.shutdown();
    try {
      if (!member.scheduler.awaitTermination(STOP_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
        errors.add(name + ": process did not stop within " + STOP_LIMIT);
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while stopping " + name, ex);
    }
  }

  private static final class Member {
    private final ScheduledExecutorService scheduler;
    private volatile boolean dead;

    private Member(ScheduledExecutorService scheduler) {
      this.scheduler = scheduler;
    }
  }
}
