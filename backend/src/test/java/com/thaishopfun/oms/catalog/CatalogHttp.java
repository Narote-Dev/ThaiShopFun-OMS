package com.thaishopfun.oms.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.auth.AuthTestSupport;
import com.thaishopfun.oms.invariant.InvariantTestTenants;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP calls with real RS256 user tokens, as in {@code AuthApiTest}. */
public final class CatalogHttp {

  public static final JsonMapper JSON = JsonMapper.builder().build();

  public record Result(int status, JsonNode body, String raw) {

    public String error() {
      return body == null ? null : body.path("error").asString();
    }
  }

  /** A provisioned shop: owner token and tenant id. */
  public record Shop(String shopId, String owner, UUID tenantId) {}

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final String base;

  public CatalogHttp(int port) {
    this.base = "http://127.0.0.1:" + port;
  }

  public Shop shop() {
    String shopId = "shop-" + UUID.randomUUID();
    String owner = token("owner-" + UUID.randomUUID(), shopId, "OWNER", "ACTIVE");
    Result me = get("/api/v1/me", owner);
    assertThat(me.status()).as(me.raw()).isEqualTo(200);
    UUID tenantId = UUID.fromString(me.body().path("tenant").path("id").asString());
    InvariantTestTenants.register(tenantId);
    return new Shop(shopId, owner, tenantId);
  }

  /** Another member of the same shop, provisioned on its first call. */
  public String member(Shop shop, String role) {
    String token = token("user-" + UUID.randomUUID(), shop.shopId(), role, "ACTIVE");
    assertThat(get("/api/v1/me", token).status()).isEqualTo(200);
    return token;
  }

  public static String token(String userId, String shopId, String role, String status) {
    return AuthTestSupport.token(
        userId,
        shopId,
        status,
        Instant.now().plus(30, ChronoUnit.DAYS),
        1,
        "oms",
        Instant.now().plusSeconds(600),
        List.of("oms"),
        role);
  }

  public Result get(String path, String token) {
    return send(builder(path, token).GET());
  }

  public Result post(String path, String token, Object body) {
    return send(json(builder(path, token), "POST", body));
  }

  public Result postWithIdempotencyKey(String path, String token, String idempotencyKey) {
    return send(json(builder(path, token).header("Idempotency-Key", idempotencyKey), "POST", ""));
  }

  public Result put(String path, String token, Object body) {
    return send(json(builder(path, token), "PUT", body));
  }

  public Result delete(String path, String token) {
    return send(builder(path, token).DELETE());
  }

  public Result upload(String path, String token, String filename, String csv) {
    return upload(path, token, filename, csv.getBytes(StandardCharsets.UTF_8));
  }

  public Result upload(String path, String token, String filename, byte[] content) {
    String boundary = "----oms" + UUID.randomUUID();
    byte[] head =
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + filename
                + "\"\r\nContent-Type: text/csv\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8);
    byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
    byte[] body = new byte[head.length + content.length + tail.length];
    System.arraycopy(head, 0, body, 0, head.length);
    System.arraycopy(content, 0, body, head.length, content.length);
    System.arraycopy(tail, 0, body, head.length + content.length, tail.length);
    return send(
        builder(path, token)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)));
  }

  private HttpRequest.Builder builder(String path, String token) {
    return HttpRequest.newBuilder(URI.create(base + path))
        .timeout(Duration.ofSeconds(60))
        .header("Authorization", "Bearer " + token)
        .header("Accept", "application/json");
  }

  private static HttpRequest.Builder json(HttpRequest.Builder builder, String method, Object body) {
    String text =
        body == null ? "" : body instanceof String raw ? raw : JSON.writeValueAsString(body);
    return builder
        .header("Content-Type", "application/json")
        .method(method, HttpRequest.BodyPublishers.ofString(text));
  }

  private Result send(HttpRequest.Builder builder) {
    try {
      HttpResponse<String> response =
          http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      String raw = response.body();
      JsonNode node = raw == null || raw.isBlank() ? null : JSON.readTree(raw);
      if (response.statusCode() >= 400) {
        assertThat(response.headers().firstValue("X-Trace-Id")).isPresent();
        assertThat(node).as(raw).isNotNull();
        assertThat(node.path("trace_id").asString()).isNotBlank();
      }
      return new Result(response.statusCode(), node, raw);
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(ex);
    }
  }

  // Superuser reads and fixtures. The API itself only ever runs as oms_app.

  public static long count(String sql, Object... params) {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement = admin.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getLong(1);
      }
    } catch (SQLException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public static String text(String sql, Object... params) {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement = admin.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
    } catch (SQLException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public static void execute(String sql, Object... params) {
    try (Connection admin = AuthTestSupport.admin();
        PreparedStatement statement = admin.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      statement.executeUpdate();
    } catch (SQLException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public static long audits(UUID tenantId, String action) {
    return count(
        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?", tenantId, action);
  }

  public static long auditsFor(UUID tenantId, String action, String entityId) {
    return count(
        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ? AND entity_id = ? "
            + "AND actor_type = 'USER' AND actor_id IS NOT NULL",
        tenantId,
        action,
        entityId);
  }

  /** Every audit row of the tenant except logins. */
  public static long catalogAudits(UUID tenantId) {
    return count(
        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action <> 'auth.login'", tenantId);
  }

  /** An inventory row seeded directly, the way T08 would create one. */
  public static void seedInventory(UUID tenantId, UUID skuId, UUID warehouseId) {
    execute(
        "INSERT INTO inventory (id, tenant_id, sku_id, warehouse_id, on_hand, reserved) "
            + "VALUES (?, ?, ?, ?, 7, 2)",
        UUID.randomUUID(),
        tenantId,
        skuId,
        warehouseId);
  }
}
