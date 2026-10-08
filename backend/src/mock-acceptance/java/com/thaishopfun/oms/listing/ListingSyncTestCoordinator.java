package com.thaishopfun.oms.listing;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test-only coordinator to interleave listing sync fetch and DB write deterministically. */
public class ListingSyncTestCoordinator implements ListingSyncCoordinator {

  private volatile CountDownLatch fetchReached;
  private volatile CountDownLatch releaseWrite;
  private volatile boolean armed;

  public void armBeforeWriteLatch() {
    armed = true;
    fetchReached = new CountDownLatch(1);
    releaseWrite = new CountDownLatch(1);
  }

  public void disarm() {
    armed = false;
    fetchReached = null;
    releaseWrite = null;
  }

  @Override
  public void afterListingsFetchedBeforeWrite() {
    if (!armed || fetchReached == null || releaseWrite == null) {
      return;
    }
    fetchReached.countDown();
    try {
      if (!releaseWrite.await(60, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting to continue listing sync write");
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted waiting to continue listing sync write", ex);
    }
  }

  public void awaitFetchComplete() throws InterruptedException {
    if (fetchReached == null) {
      throw new IllegalStateException("listing sync latch not armed");
    }
    if (!fetchReached.await(60, TimeUnit.SECONDS)) {
      throw new IllegalStateException("timed out waiting for listing sync fetch");
    }
  }

  public void continueWrite() {
    if (releaseWrite != null) {
      releaseWrite.countDown();
    }
  }
}
