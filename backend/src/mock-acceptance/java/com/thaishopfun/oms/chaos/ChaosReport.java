package com.thaishopfun.oms.chaos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Collects one section per direction and writes {@code target/chaos-report.json}. The file is
 * rewritten after every section, so a failing direction still leaves the other one on disk.
 */
final class ChaosReport {

  static final Path FILE = Path.of("target", "chaos-report.json");

  private static final Logger log = LoggerFactory.getLogger(ChaosReport.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Map<String, Object> root = new LinkedHashMap<>();

  ChaosReport(long seed) {
    root.put("seed", seed);
    root.put("seed_property", "chaos.seed");
  }

  /**
   * One direction. {@code duplicate_rate = total / unique - 1}; the assertions are on correctness,
   * this number is only reported.
   */
  static Map<String, Object> direction(
      int eventsSent,
      int uniqueDelivered,
      int totalDeliveries,
      Map<String, Integer> retriesByCause,
      long wallTimeMs) {
    Map<String, Object> section = new LinkedHashMap<>();
    section.put("events_sent", eventsSent);
    section.put("unique_delivered", uniqueDelivered);
    section.put("total_deliveries", totalDeliveries);
    double rate = uniqueDelivered == 0 ? 0 : ((double) totalDeliveries / uniqueDelivered) - 1;
    section.put("duplicate_rate", Math.round(rate * 10_000) / 10_000.0);
    section.put("retries_by_cause", retriesByCause);
    section.put("wall_time_ms", wallTimeMs);
    return section;
  }

  synchronized void put(String direction, Map<String, Object> section) {
    root.put(direction, section);
    String pretty = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    log.info("chaos report {}:\n{}", direction, pretty);
    try {
      Files.createDirectories(FILE.getParent());
      Files.writeString(FILE, pretty + "\n");
    } catch (IOException ex) {
      throw new IllegalStateException("could not write " + FILE, ex);
    }
  }
}
