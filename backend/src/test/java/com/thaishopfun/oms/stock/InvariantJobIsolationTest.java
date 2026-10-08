package com.thaishopfun.oms.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.thaishopfun.oms.invariant.InvariantChecker;
import com.thaishopfun.oms.invariant.InvariantCodes;
import com.thaishopfun.oms.invariant.InvariantJob;
import com.thaishopfun.oms.invariant.Violation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class InvariantJobIsolationTest {

  @Test
  void runOnceContinuesWhenOneTenantThrows() {
    InvariantChecker checker = mock(InvariantChecker.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    InvariantJob job = new InvariantJob(checker, jdbc, meters);

    UUID failing = UUID.randomUUID();
    UUID ok = UUID.randomUUID();
    when(checker.checkSchema()).thenReturn(List.of());
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenReturn(List.of(failing, ok));
    when(checker.checkTenant(any()))
        .thenAnswer(
            inv -> {
              UUID tenantId = inv.getArgument(0);
              if (tenantId.equals(failing)) {
                throw new RuntimeException("simulated checker failure");
              }
              return List.<Violation>of();
            });

    job.runOnce();

    verify(checker, atLeastOnce()).checkTenant(eq(ok));
    assertThat(
            meters
                .find(InvariantJob.VIOLATIONS_METRIC)
                .tag("code", InvariantCodes.CHECK_FAILED)
                .counter())
        .isNotNull();
    assertThat(
            meters
                .find(InvariantJob.VIOLATIONS_METRIC)
                .tag("code", InvariantCodes.CHECK_FAILED)
                .counter()
                .count())
        .isEqualTo(1.0);
  }
}
