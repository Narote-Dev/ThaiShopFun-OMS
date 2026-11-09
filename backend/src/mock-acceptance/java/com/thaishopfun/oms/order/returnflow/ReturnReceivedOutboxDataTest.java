package com.thaishopfun.oms.order.returnflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.mocktsf.contract.ContractValidator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReturnReceivedOutboxDataTest {

  private static final ContractValidator CONTRACT = ContractValidator.classpath();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void mixedLineEmitsOneContractRowPerCondition() throws Exception {
    UUID lineId = UUID.randomUUID();
    UUID returnId = UUID.randomUUID();
    List<Map<String, Object>> lines =
        ReturnService.contractReceivedLines(
            List.of(
                new ReturnRepository.ReturnLineRow(
                    lineId,
                    UUID.randomUUID(),
                    returnId,
                    UUID.randomUUID(),
                    "L1",
                    2,
                    "MIXED",
                    1,
                    1,
                    UUID.randomUUID())));
    assertThat(lines)
        .containsExactly(
            Map.of("line_id", "L1", "qty", 1, "condition", "RESELLABLE"),
            Map.of("line_id", "L1", "qty", 1, "condition", "DAMAGED"));
    String envelope =
        JSON.writeValueAsString(
            Map.of(
                "event_id",
                "01J9Z3K7Q8returnreceiv",
                "event_type",
                "return.received",
                "schema_version",
                1,
                "occurred_at",
                "2026-09-29T08:15:02Z",
                "tsf_shop_id",
                "shop_45021",
                "aggregate_id",
                "ret-1",
                "aggregate_version",
                9,
                "data",
                Map.of("order_id", "TSF-1", "return_id", "ret-1", "lines", lines)));
    assertThat(CONTRACT.envelopeErrors(envelope)).isEmpty();
  }
}
