package com.thaishopfun.oms.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Outcome of a committed import. A re-import of the same file is all {@code skus_unchanged}. */
public record ImportResult(
    int rows,
    @JsonProperty("products_created") int productsCreated,
    @JsonProperty("skus_created") int skusCreated,
    @JsonProperty("skus_updated") int skusUpdated,
    @JsonProperty("skus_unchanged") int skusUnchanged,
    @JsonProperty("bundles_replaced") int bundlesReplaced,
    @JsonProperty("elapsed_ms") long elapsedMs) {

  ImportResult withElapsed(long millis) {
    return new ImportResult(
        rows, productsCreated, skusCreated, skusUpdated, skusUnchanged, bundlesReplaced, millis);
  }
}
