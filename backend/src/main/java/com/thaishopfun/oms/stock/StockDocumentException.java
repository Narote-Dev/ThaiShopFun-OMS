package com.thaishopfun.oms.stock;

import java.util.List;

/**
 * A post or void refused for a business reason. Unlike the reservation errors it is not stored
 * under the idempotency key: the transaction rolls back, so the draft can be fixed and posted
 * again. {@code problems} lists every offending line (all lines post or none do).
 */
public class StockDocumentException extends StockOperationException {

  private final List<LineProblem> problems;

  public StockDocumentException(StockError error, String message) {
    this(error, message, List.of());
  }

  public StockDocumentException(StockError error, String message, List<LineProblem> problems) {
    super(error, message);
    this.problems = List.copyOf(problems);
  }

  public List<LineProblem> problems() {
    return problems;
  }
}
