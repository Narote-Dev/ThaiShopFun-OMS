package com.thaishopfun.oms.inbox;

import com.thaishopfun.oms.order.backfill.OrderGapRefetch;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Inbox API tests stub TSF REST; they only need gap detection to mark rows processed. */
@Profile("inbox-api-test")
@Primary
@Component
public class RecordingOrderGapRefetch implements OrderGapRefetch {

  public record Call(UUID tenantId, String shopId, String externalOrderId, long aggregateVersion) {}

  private final List<Call> calls = new ArrayList<>();

  @Override
  public void refetchAndApply(
      UUID tenantId,
      String shopId,
      String externalOrderId,
      long inboxAggregateVersion,
      String prefix) {
    calls.add(new Call(tenantId, shopId, externalOrderId, inboxAggregateVersion));
  }

  public List<Call> calls() {
    return List.copyOf(calls);
  }

  public void reset() {
    calls.clear();
  }
}
