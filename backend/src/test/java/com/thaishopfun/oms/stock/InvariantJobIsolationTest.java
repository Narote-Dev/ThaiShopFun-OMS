package com.thaishopfun.oms.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.thaishopfun.oms.invariant.InvariantChecker;
import com.thaishopfun.oms.invariant.InvariantCodes;
import com.thaishopfun.oms.invariant.InvariantJob;
import com.thaishopfun.oms.invariant.SkipInvariantCheck;
import com.thaishopfun.oms.invariant.Violation;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

@SkipInvariantCheck("Mocks checker failures")
class InvariantJobIsolationTest extends StockTestBase {

  @MockBean InvariantChecker checker;

  @Autowired InvariantJob job;
  @Autowired MeterRegistry meters;

  @Test
  void runOnceContinuesWhenOneTenantThrows() {
    StockFixture.Shop failing = fixture.shop("ACTIVE");
    StockFixture.Shop ok = fixture.shop("ACTIVE");
    when(checker.checkSchema()).thenReturn(List.of());
    when(checker.checkTenant(any()))
        .thenAnswer(
            inv -> {
              UUID tenantId = inv.getArgument(0);
              if (tenantId.equals(failing.tenant())) {
                throw new RuntimeException("simulated checker failure");
              }
              return List.<Violation>of();
            });

    double before = metricCount(InvariantCodes.CHECK_FAILED);
    job.runOnce();
    verify(checker, atLeastOnce()).checkTenant(ok.tenant());
    assertThat(metricCount(InvariantCodes.CHECK_FAILED)).isGreaterThan(before);
  }

  private double metricCount(String code) {
    var counter = meters.find(InvariantJob.VIOLATIONS_METRIC).tag("code", code).counter();
    return counter == null ? 0 : counter.count();
  }
}
