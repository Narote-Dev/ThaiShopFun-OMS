package com.thaishopfun.mocktsf;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thaishopfun.mocktsf.contract.ContractValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every example under contracts/examples matches its schema. */
class ContractExamplesTest {

  @Test
  void examplesMatchSchemas() throws Exception {
    Path root = contractsRoot();
    ContractValidator validator = ContractValidator.directory(root);
    List<String> failures = new ArrayList<>();
    try (Stream<Path> events = Files.list(root.resolve("examples/events"))) {
      events
          .filter(path -> path.toString().endsWith(".json"))
          .sorted()
          .forEach(
              path -> {
                try {
                  List<String> errors = validator.envelopeErrors(Files.readString(path));
                  if (!errors.isEmpty()) {
                    failures.add(path.getFileName() + ": " + errors);
                  }
                } catch (Exception ex) {
                  failures.add(path.getFileName() + ": " + ex.getMessage());
                }
              });
    }
    try (Stream<Path> rest = Files.list(root.resolve("examples/rest"))) {
      rest.filter(path -> path.toString().endsWith(".json"))
          .sorted()
          .forEach(
              path -> {
                String name = path.getFileName().toString().replaceFirst("\\.json$", "");
                try {
                  List<String> errors = validator.restErrors(name, Files.readString(path));
                  if (!errors.isEmpty()) {
                    failures.add(path.getFileName() + ": " + errors);
                  }
                } catch (Exception ex) {
                  failures.add(path.getFileName() + ": " + ex.getMessage());
                }
              });
    }
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  static Path contractsRoot() {
    Path direct = Path.of("contracts");
    if (Files.isDirectory(direct.resolve("schemas"))) {
      return direct;
    }
    Path sibling = Path.of("..", "contracts");
    if (Files.isDirectory(sibling.resolve("schemas"))) {
      return sibling;
    }
    throw new IllegalStateException("contracts directory not found");
  }
}
