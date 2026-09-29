package com.thaishopfun.oms.inbox;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /internal/v1/events}. Service JWT plus HMAC. Ack is 202, duplicate is 200. */
@RestController
public class InboxController {

  public static final String ACK_METRIC = "oms.inbox.ack";

  private final InboxIngestService ingest;
  private final MeterRegistry meters;

  public InboxController(InboxIngestService ingest, MeterRegistry meters) {
    this.ingest = ingest;
    this.meters = meters;
  }

  @PostMapping(path = "/internal/v1/events", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, String>> receive(HttpServletRequest request) {
    long started = System.nanoTime();
    InboxIngestService.IngestResult result = ingest.receive(request);
    // Step 1: Time the ack after authentication. The NFR sample is this timer.
    meters
        .timer(ACK_METRIC, "result", resultLabel(result.httpStatus()))
        .record(Duration.ofNanos(System.nanoTime() - started));
    ResponseEntity.BodyBuilder response = ResponseEntity.status(result.httpStatus());
    if (result.retryAfterSeconds() != null) {
      response.header(HttpHeaders.RETRY_AFTER, Integer.toString(result.retryAfterSeconds()));
    }
    return response.body(result.body());
  }

  private static String resultLabel(int status) {
    if (status == 202) {
      return "accepted";
    }
    if (status == 200) {
      return "duplicate";
    }
    if (status == 401) {
      return "unauthorized";
    }
    if (status == 503) {
      return "unavailable";
    }
    return "rejected";
  }
}
