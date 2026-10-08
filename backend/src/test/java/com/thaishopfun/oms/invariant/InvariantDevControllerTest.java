package com.thaishopfun.oms.invariant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class InvariantDevControllerTest {

  @Test
  void returnsOkWhenHealthy() {
    InvariantJob job = mock(InvariantJob.class);
    when(job.runOnce()).thenReturn(new InvariantJobResult(0, 0));
    InvariantDevController controller = new InvariantDevController(job);

    var response = controller.runOnce();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsEntry("status", "OK");
    assertThat(response.getBody()).containsEntry("checkFailed", 0);
  }

  @Test
  void returnsFailedWhenCheckFailedPositive() {
    InvariantJob job = mock(InvariantJob.class);
    when(job.runOnce()).thenReturn(new InvariantJobResult(0, 2));
    InvariantDevController controller = new InvariantDevController(job);

    var response = controller.runOnce();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).containsEntry("status", "FAILED");
    assertThat(response.getBody()).containsEntry("checkFailed", 2);
  }
}
