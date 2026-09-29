package com.thaishopfun.mocktsf.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.thaishopfun.mocktsf.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JSON Schema checks for envelopes, event {@code data}, and REST bodies under {@code contracts/}.
 */
public final class ContractValidator {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final SchemaSource source;
  private final JsonSchemaFactory factory;
  private final SchemaValidatorsConfig config;
  private final ConcurrentHashMap<String, JsonSchema> cache = new ConcurrentHashMap<>();

  private ContractValidator(SchemaSource source) {
    this.source = source;
    this.factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    this.config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
  }

  public static ContractValidator classpath() {
    return new ContractValidator(ContractValidator::classpathResource);
  }

  public static ContractValidator directory(Path root) {
    return new ContractValidator(relative -> directoryResource(root, relative));
  }

  public void requireEnvelope(String json) {
    List<String> errors = envelopeErrors(json);
    if (!errors.isEmpty()) {
      throw ApiException.schema(join(errors));
    }
  }

  public void requireRest(String schemaName, String json) {
    List<String> errors = restErrors(schemaName, json);
    if (!errors.isEmpty()) {
      throw ApiException.schema(schemaName + ": " + join(errors));
    }
  }

  public List<String> envelopeErrors(String json) {
    JsonNode node = read(json);
    List<String> errors = new ArrayList<>(messages(schema("schemas/envelope.json"), node));
    if (!errors.isEmpty()) {
      return errors;
    }
    JsonNode type = node.get("event_type");
    JsonNode data = node.get("data");
    if (type == null || !type.isTextual() || data == null || !data.isObject()) {
      return errors;
    }
    String eventType = type.asText();
    String relative = "schemas/events/" + eventType + ".json";
    if (source.read(relative) == null) {
      errors.add("unknown event_type");
      return errors;
    }
    errors.addAll(messages(schema(relative), data));
    return errors;
  }

  public List<String> restErrors(String schemaName, String json) {
    return messages(schema("schemas/rest/" + schemaName + ".json"), read(json));
  }

  private JsonSchema schema(String relative) {
    return cache.computeIfAbsent(
        relative,
        key -> {
          String text = source.read(key);
          if (text == null) {
            throw ApiException.schema("schema not found: " + key);
          }
          try {
            return factory.getSchema(JSON.readTree(text), config);
          } catch (IOException ex) {
            throw ApiException.schema("schema is invalid: " + key);
          }
        });
  }

  private static List<String> messages(JsonSchema schema, JsonNode node) {
    Set<ValidationMessage> found = schema.validate(node);
    List<String> errors = new ArrayList<>();
    for (ValidationMessage message : found) {
      errors.add(message.getMessage());
    }
    return errors;
  }

  private static JsonNode read(String json) {
    try {
      JsonNode node = JSON.readTree(json);
      if (node == null || !node.isObject()) {
        throw ApiException.schema("body must be a JSON object");
      }
      return node;
    } catch (IOException ex) {
      throw ApiException.schema("body must be a JSON object");
    }
  }

  private static String join(List<String> errors) {
    String text = String.join("; ", errors);
    if (text.length() > 500) {
      return text.substring(0, 500);
    }
    return text;
  }

  private interface SchemaSource {
    String read(String relative);
  }

  private static String classpathResource(String relative) {
    String path = "/contracts/" + relative;
    try (InputStream in = ContractValidator.class.getResourceAsStream(path)) {
      if (in == null) {
        return null;
      }
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (IOException ex) {
      return null;
    }
  }

  private static String directoryResource(Path root, String relative) {
    Path file = root.resolve(relative);
    if (!Files.isRegularFile(file)) {
      return null;
    }
    try {
      return Files.readString(file);
    } catch (IOException ex) {
      return null;
    }
  }
}
