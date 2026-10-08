package com.thaishopfun.oms.listing;

/**
 * Optional hook for tests to interleave work between channel fetch and DB write during listing
 * sync.
 */
public interface ListingSyncCoordinator {

  default void afterListingsFetchedBeforeWrite() {}
}
