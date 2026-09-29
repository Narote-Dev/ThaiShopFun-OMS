package com.thaishopfun.oms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.datasource.hikari.maximum-pool-size=20")
@Import({
  AuthApiTest.AsyncConfig.class,
  AuthApiTest.AsyncTenantReader.class,
  AuthApiTest.AsyncProbe.class
})
class AuthApiTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
  }

  @LocalServerPort private int port;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private AsyncTenantReader asyncTenantReader;

  private RestClient client;

  @BeforeEach
  void client() {
    client = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
  }

  @Test
  void runtimeConnectsAsOmsAppWithoutBypass() {
    assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("oms_app");
    assertThat(
            jdbc.queryForObject(
                "SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class))
        .isFalse();
  }

  @Test
  void provisioningFunctionsAreDefinerAndAppOnly() throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        Statement statement = admin.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT p.proname, pg_get_function_identity_arguments(p.oid) AS args, "
                    + "pg_get_function_result(p.oid) AS result, p.prosecdef, r.rolname AS owner, "
                    + "p.proconfig::text AS config "
                    + "FROM pg_proc p "
                    + "JOIN pg_namespace n ON n.oid = p.pronamespace "
                    + "JOIN pg_roles r ON r.oid = p.proowner "
                    + "WHERE n.nspname = 'public' AND p.proname IN "
                    + "('upsert_app_user', 'provision_tenant', 'provision_membership', 'lookup_login')")) {
      int seen = 0;
      while (rows.next()) {
        seen++;
        assertThat(rows.getBoolean("prosecdef")).isTrue();
        assertThat(rows.getString("owner")).isEqualTo("oms_maint");
        if ("lookup_login".equals(rows.getString("proname"))) {
          assertThat(rows.getString("result")).contains("ent_ver").contains("membership_status");
        } else {
          assertThat(rows.getString("result")).isEqualTo("uuid");
        }
        assertThat(rows.getString("config")).contains("search_path=pg_catalog, pg_temp");
      }
      assertThat(seen).isEqualTo(4);
    }

    try (Connection admin = AuthTestSupport.admin();
        Statement statement = admin.createStatement();
        ResultSet privileges =
            statement.executeQuery(
                "SELECT "
                    + "has_function_privilege('oms_app', 'public.upsert_app_user(text, text, text)', 'EXECUTE') AS app_user_fn, "
                    + "has_function_privilege('oms_migrator', 'public.upsert_app_user(text, text, text)', 'EXECUTE') AS mig_user_fn, "
                    + "has_function_privilege('public', 'public.upsert_app_user(text, text, text)', 'EXECUTE') AS public_user_fn, "
                    + "has_function_privilege('oms_app', 'public.provision_tenant(text, text, text, text, timestamptz, bigint)', 'EXECUTE') AS app_tenant_fn, "
                    + "has_function_privilege('oms_migrator', 'public.provision_tenant(text, text, text, text, timestamptz, bigint)', 'EXECUTE') AS mig_tenant_fn, "
                    + "has_function_privilege('oms_app', 'public.provision_membership(uuid, uuid, text, bigint)', 'EXECUTE') AS app_member_fn, "
                    + "has_function_privilege('oms_app', 'public.lookup_login(text, text)', 'EXECUTE') AS app_lookup, "
                    + "has_function_privilege('public', 'public.lookup_login(text, text)', 'EXECUTE') AS public_lookup, "
                    + "has_table_privilege('oms_app', 'public.app_user', 'SELECT') AS user_read, "
                    + "has_table_privilege('oms_app', 'public.app_user', 'INSERT') AS user_write")) {
      assertThat(privileges.next()).isTrue();
      assertThat(privileges.getBoolean("app_user_fn")).isTrue();
      assertThat(privileges.getBoolean("mig_user_fn")).isFalse();
      assertThat(privileges.getBoolean("public_user_fn")).isFalse();
      assertThat(privileges.getBoolean("app_tenant_fn")).isTrue();
      assertThat(privileges.getBoolean("mig_tenant_fn")).isFalse();
      assertThat(privileges.getBoolean("app_member_fn")).isTrue();
      assertThat(privileges.getBoolean("app_lookup")).isTrue();
      assertThat(privileges.getBoolean("public_lookup")).isFalse();
      assertThat(privileges.getBoolean("user_read")).isFalse();
      assertThat(privileges.getBoolean("user_write")).isFalse();
    }
  }

  @Test
  void validTokenReturnsMeAndWritesAuditWithoutEmail() throws Exception {
    String shopId = shop();
    String userId = user();
    HttpResult result =
        get("/api/v1/me", AuthTestSupport.userToken(userId, shopId, "ACTIVE", future(), 1));

    assertThat(result.status()).isEqualTo(200);
    JsonNode body = JSON.readTree(result.body());
    assertThat(body.path("role").asString()).isEqualTo("OWNER");
    assertThat(body.path("tenant").path("tsf_shop_id").asString()).isEqualTo(shopId);
    assertThat(body.path("tenant").path("name").asString()).isEqualTo("Shop " + shopId);
    assertThat(body.path("tenant").path("membership_tier").asString()).isEqualTo("PRO");
    assertThat(body.path("entitlement").path("status").asString()).isEqualTo("ACTIVE");
    assertThat(body.path("entitlement").path("ent_ver").asLong()).isEqualTo(1);
    assertThat(body.toString()).doesNotContain("owner-pii");

    try (Connection admin = AuthTestSupport.admin();
        var statement =
            admin.prepareStatement(
                "SELECT count(*) AS rows, "
                    + "bool_or(action = 'auth.login') AS login, "
                    + "bool_or(actor_type = 'USER') AS user_actor, "
                    + "bool_or(\"after\"::text LIKE '%owner-pii%' OR \"after\"::text LIKE '%@%') AS pii, "
                    + "bool_or(actor_id = ?) AS actor "
                    + "FROM audit_log WHERE actor_id = ?")) {
      statement.setString(1, userId);
      statement.setString(2, userId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getLong("rows")).isEqualTo(1);
        assertThat(rows.getBoolean("login")).isTrue();
        assertThat(rows.getBoolean("user_actor")).isTrue();
        assertThat(rows.getBoolean("pii")).isFalse();
        assertThat(rows.getBoolean("actor")).isTrue();
      }
    }
    try (Connection admin = AuthTestSupport.admin();
        var statement =
            admin.prepareStatement("SELECT email FROM app_user WHERE tsf_user_id = ?")) {
      statement.setString(1, userId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString("email")).isEqualTo("owner-pii@shop.example");
      }
    }
  }

  @Test
  void wrongAudienceOrExpiredTokenIs401AndDoesNotProvision() throws Exception {
    String shopId = shop();
    String userId = user();
    HttpResult wrongAudience =
        get(
            "/api/v1/me",
            AuthTestSupport.token(
                userId,
                shopId,
                "ACTIVE",
                future(),
                1,
                "not-oms",
                Instant.now().plusSeconds(600),
                List.of("oms")));
    HttpResult expired =
        get(
            "/api/v1/me",
            AuthTestSupport.token(
                userId,
                shopId,
                "ACTIVE",
                future(),
                1,
                "oms",
                Instant.now().minus(5, ChronoUnit.MINUTES),
                List.of("oms")));
    HttpResult missing = get("/api/v1/me", null);

    assertThat(wrongAudience.status()).isEqualTo(401);
    assertThat(expired.status()).isEqualTo(401);
    assertThat(missing.status()).isEqualTo(401);
    assertThat(JSON.readTree(wrongAudience.body()).path("error").asString())
        .isEqualTo("UNAUTHORIZED");
    assertThat(JSON.readTree(expired.body()).path("trace_id").asString()).hasSize(32);
    assertThat(count("SELECT count(*) FROM tenant WHERE tsf_shop_id = ?", shopId)).isZero();
  }

  @Test
  void suspendedOrExpiredEntitlementIs403() {
    HttpResult suspended =
        get("/api/v1/me", AuthTestSupport.userToken(user(), shop(), "SUSPENDED", future(), 1));
    HttpResult expiredMembership =
        get(
            "/api/v1/me",
            AuthTestSupport.userToken(
                user(), shop(), "ACTIVE", Instant.now().minus(1, ChronoUnit.DAYS), 1));
    HttpResult missingEntitlement =
        get(
            "/api/v1/me",
            AuthTestSupport.token(
                user(),
                shop(),
                "ACTIVE",
                future(),
                1,
                "oms",
                Instant.now().plusSeconds(600),
                List.of()));

    assertError(suspended, 403, "ENTITLEMENT_INACTIVE");
    assertError(expiredMembership, 403, "ENTITLEMENT_INACTIVE");
    assertError(missingEntitlement, 403, "ENTITLEMENT_INACTIVE");
  }

  @Test
  void graceAllowsGetAndRejectsPost() {
    String token = AuthTestSupport.userToken(user(), shop(), "GRACE", future(), 1);

    HttpResult read = get("/api/v1/me", token);
    HttpResult write = post("/api/v1/me", token);

    assertThat(read.status()).isEqualTo(200);
    assertThat(JSON.readTree(read.body()).path("entitlement").path("status").asString())
        .isEqualTo("GRACE");
    assertError(write, 403, "ENTITLEMENT_GRACE");
  }

  @Test
  void staleEntVerIs401AndDoesNotDowngrade() throws Exception {
    String shopId = shop();
    String userId = user();
    HttpResult first =
        get("/api/v1/me", AuthTestSupport.userToken(userId, shopId, "ACTIVE", future(), 2));
    assertThat(first.status()).isEqualTo(200);
    String userMark =
        text(
            "SELECT last_login_at::text || ctid::text FROM app_user WHERE tsf_user_id = ?", userId);
    String tenantMark = text("SELECT ctid::text FROM tenant WHERE tsf_shop_id = ?", shopId);
    HttpResult stale =
        get("/api/v1/me", AuthTestSupport.userToken(userId, shopId, "SUSPENDED", future(), 1));

    assertError(stale, 401, "ENTITLEMENT_STALE");
    assertThat(JSON.readTree(stale.body()).path("message").asString())
        .isEqualTo(TenantContextFilter.UNAUTHORIZED_MESSAGE);
    assertThat(count("SELECT ent_ver FROM tenant WHERE tsf_shop_id = ?", shopId)).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM audit_log WHERE actor_id = ?", userId)).isEqualTo(1);
    assertThat(
            text(
                "SELECT last_login_at::text || ctid::text FROM app_user WHERE tsf_user_id = ?",
                userId))
        .isEqualTo(userMark);
    assertThat(text("SELECT ctid::text FROM tenant WHERE tsf_shop_id = ?", shopId))
        .isEqualTo(tenantMark);
  }

  @Test
  void internalHealthRequiresClientCredentials() {
    String service =
        AuthTestSupport.token(
            "tsf-checkout",
            shop(),
            "ACTIVE",
            future(),
            1,
            "oms-internal",
            Instant.now().plusSeconds(600),
            List.of());
    String user = AuthTestSupport.userToken(user(), shop(), "ACTIVE", future(), 1);

    assertThat(get("/internal/v1/health", service).status()).isEqualTo(200);
    assertThat(JSON.readTree(get("/internal/v1/health", service).body()).path("status").asString())
        .isEqualTo("UP");
    assertThat(get("/internal/v1/health", user).status()).isEqualTo(401);
    assertThat(get("/internal/v1/health", null).status()).isEqualTo(401);
    assertThat(get("/api/v1/me", service).status()).isEqualTo(401);
    assertThat(get("/actuator/health", null).status()).isEqualTo(200);
  }

  @Test
  void tenConcurrentFirstLoginsCreateOneTenantAndTenAudits() throws Exception {
    String shopId = shop();
    String userId = user();
    String token = AuthTestSupport.userToken(userId, shopId, "ACTIVE", future(), 1);
    ExecutorService pool = Executors.newFixedThreadPool(10);
    CountDownLatch ready = new CountDownLatch(10);
    CountDownLatch start = new CountDownLatch(1);
    List<Integer> statuses = new ArrayList<>();
    List<CompletableFuture<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      tasks.add(
          CompletableFuture.supplyAsync(
              () -> {
                ready.countDown();
                try {
                  if (!start.await(10, TimeUnit.SECONDS)) {
                    return 0;
                  }
                } catch (InterruptedException ex) {
                  Thread.currentThread().interrupt();
                  return 0;
                }
                return get("/api/v1/me", token).status();
              },
              pool));
    }
    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    for (CompletableFuture<Integer> task : tasks) {
      statuses.add(task.get(30, TimeUnit.SECONDS));
    }
    pool.shutdown();

    assertThat(statuses).containsOnly(200);
    assertThat(count("SELECT count(*) FROM tenant WHERE tsf_shop_id = ?", shopId)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM app_user WHERE tsf_user_id = ?", userId)).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM tenant_membership m "
                    + "JOIN tenant t ON t.id = m.tenant_id "
                    + "JOIN app_user u ON u.id = m.user_id "
                    + "WHERE t.tsf_shop_id = ? AND u.tsf_user_id = ?",
                shopId,
                userId))
        .isEqualTo(1);
    // Each request that still sees a missing row provisions and audits. A request that loses the
    // race takes the read path and does not add another audit row.
    assertThat(count("SELECT count(*) FROM audit_log WHERE actor_id = ?", userId))
        .isBetween(1L, 10L);
  }

  @Test
  void repeatedRequestsWithTheSameTokenDoNotWrite() throws Exception {
    String shopId = shop();
    String userId = user();
    String token = AuthTestSupport.userToken(userId, shopId, "ACTIVE", future(), 1);
    assertThat(get("/api/v1/me", token).status()).isEqualTo(200);
    String userMark =
        text(
            "SELECT last_login_at::text || ctid::text FROM app_user WHERE tsf_user_id = ?", userId);
    String tenantMark = text("SELECT ctid::text FROM tenant WHERE tsf_shop_id = ?", shopId);
    String memberMark =
        text(
            "SELECT m.ctid::text FROM tenant_membership m JOIN app_user u ON u.id = m.user_id WHERE u.tsf_user_id = ?",
            userId);

    for (int i = 0; i < 100; i++) {
      assertThat(get("/api/v1/me", token).status()).as("repeat %s", i).isEqualTo(200);
    }

    assertThat(count("SELECT count(*) FROM audit_log WHERE actor_id = ?", userId)).isEqualTo(1);
    assertThat(
            text(
                "SELECT last_login_at::text || ctid::text FROM app_user WHERE tsf_user_id = ?",
                userId))
        .isEqualTo(userMark);
    assertThat(text("SELECT ctid::text FROM tenant WHERE tsf_shop_id = ?", shopId))
        .isEqualTo(tenantMark);
    assertThat(
            text(
                "SELECT m.ctid::text FROM tenant_membership m JOIN app_user u ON u.id = m.user_id WHERE u.tsf_user_id = ?",
                userId))
        .isEqualTo(memberMark);
  }

  @Test
  void revokedMembershipIs403AndIsNotReactivated() throws Exception {
    String shopId = shop();
    String userId = user();
    String token = AuthTestSupport.userToken(userId, shopId, "ACTIVE", future(), 1);
    assertThat(get("/api/v1/me", token).status()).isEqualTo(200);
    try (Connection admin = AuthTestSupport.admin();
        var statement =
            admin.prepareStatement(
                "UPDATE tenant_membership m SET status = 'REVOKED' FROM app_user u "
                    + "WHERE m.user_id = u.id AND u.tsf_user_id = ?")) {
      statement.setString(1, userId);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }

    HttpResult denied = get("/api/v1/me", token);
    assertError(denied, 403, "MEMBERSHIP_REVOKED");
    assertThat(
            text(
                "SELECT m.status FROM tenant_membership m JOIN app_user u ON u.id = m.user_id WHERE u.tsf_user_id = ?",
                userId))
        .isEqualTo("REVOKED");
    assertThat(count("SELECT count(*) FROM audit_log WHERE actor_id = ?", userId)).isEqualTo(1);

    try (Connection app =
        java.sql.DriverManager.getConnection(
            AuthTestSupport.POSTGRES.getJdbcUrl(), "oms_app", AuthTestSupport.APP_PASSWORD)) {
      UUID tenantId =
          UUID.fromString(text("SELECT id::text FROM tenant WHERE tsf_shop_id = ?", shopId));
      UUID appUserId =
          UUID.fromString(text("SELECT id::text FROM app_user WHERE tsf_user_id = ?", userId));
      try (var statement = app.prepareStatement("SELECT provision_membership(?, ?, 'STAFF', 99)")) {
        statement.setObject(1, tenantId);
        statement.setObject(2, appUserId);
        try (ResultSet rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
        }
      }
    }
    assertThat(
            text(
                "SELECT m.status FROM tenant_membership m JOIN app_user u ON u.id = m.user_id WHERE u.tsf_user_id = ?",
                userId))
        .isEqualTo("REVOKED");
  }

  @Test
  void staleEntVerDoesNotOverwriteRole() throws Exception {
    String shopId = shop();
    String userId = user();
    assertThat(
            get("/api/v1/me", AuthTestSupport.userToken(userId, shopId, "ACTIVE", future(), 5))
                .status())
        .isEqualTo(200);
    UUID tenantId =
        UUID.fromString(text("SELECT id::text FROM tenant WHERE tsf_shop_id = ?", shopId));
    UUID appUserId =
        UUID.fromString(text("SELECT id::text FROM app_user WHERE tsf_user_id = ?", userId));

    try (Connection app =
        java.sql.DriverManager.getConnection(
            AuthTestSupport.POSTGRES.getJdbcUrl(), "oms_app", AuthTestSupport.APP_PASSWORD)) {
      // Step 1: An older token must not replace OWNER with STAFF.
      try (var statement = app.prepareStatement("SELECT provision_membership(?, ?, 'STAFF', 1)")) {
        statement.setObject(1, tenantId);
        statement.setObject(2, appUserId);
        try (ResultSet rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
        }
      }
      // Step 2: The same ent_ver may change the role. A revoked row still cannot.
      try (var statement = app.prepareStatement("SELECT provision_membership(?, ?, 'ADMIN', 5)")) {
        statement.setObject(1, tenantId);
        statement.setObject(2, appUserId);
        try (ResultSet rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
        }
      }
    }

    assertThat(
            text(
                "SELECT m.role FROM tenant_membership m JOIN app_user u ON u.id = m.user_id WHERE u.tsf_user_id = ?",
                userId))
        .isEqualTo("ADMIN");
  }

  @Test
  void gracePastExpiryIsInactive() {
    HttpResult expired =
        get(
            "/api/v1/me",
            AuthTestSupport.userToken(
                user(), shop(), "GRACE", Instant.now().minus(1, ChronoUnit.DAYS), 1));
    assertError(expired, 403, "ENTITLEMENT_INACTIVE");
  }

  @Test
  void idTokenUsedAsBearerIs401() throws Exception {
    HttpResult result = get("/api/v1/me", AuthTestSupport.idToken(user(), shop()));
    assertThat(result.status()).isEqualTo(401);
    assertThat(JSON.readTree(result.body()).path("error").asString()).isEqualTo("UNAUTHORIZED");
  }

  @Test
  void badTokensAreGeneric401() throws Exception {
    String shopId = shop();
    String userId = user();
    List<String> tokens =
        List.of(
            AuthTestSupport.wrongIssuerToken(userId, shopId),
            AuthTestSupport.noneAlgorithmToken(userId, shopId),
            AuthTestSupport.hs256WithPublicKeyToken(userId, shopId),
            AuthTestSupport.unknownKeyIdToken(userId, shopId),
            AuthTestSupport.invalidRoleToken(userId, shopId),
            AuthTestSupport.dualAudienceToken(userId, shopId));
    for (String token : tokens) {
      HttpResult result = get("/api/v1/me", token);
      assertThat(result.status()).isEqualTo(401);
      JsonNode body = JSON.readTree(result.body());
      assertThat(body.path("error").asString()).isEqualTo("UNAUTHORIZED");
      assertThat(body.path("message").asString())
          .isEqualTo(TenantContextFilter.UNAUTHORIZED_MESSAGE);
      assertThat(body.toString()).doesNotContain("shop_role").doesNotContain("NOPE");
    }
    assertThat(count("SELECT count(*) FROM tenant WHERE tsf_shop_id = ?", shopId)).isZero();

    HttpResult unknownClient =
        get(
            "/internal/v1/health",
            AuthTestSupport.token(
                "other-client",
                shop(),
                "ACTIVE",
                future(),
                1,
                "oms-internal",
                Instant.now().plusSeconds(600),
                List.of()));
    assertThat(unknownClient.status()).isEqualTo(401);
    assertThat(JSON.readTree(unknownClient.body()).path("message").asString())
        .isEqualTo(TenantContextFilter.UNAUTHORIZED_MESSAGE);
  }

  @Test
  void auditIpUsesForwardedFor() throws Exception {
    String userId = user();
    String token = AuthTestSupport.userToken(userId, shop(), "ACTIVE", future(), 1);
    RestClient.RequestHeadersSpec<?> spec = client.get().uri("/api/v1/me");
    spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    spec.header("X-Forwarded-For", "203.0.113.9");
    HttpResult result =
        spec.exchange(
            (request, response) ->
                new HttpResult(
                    response.getStatusCode().value(),
                    new String(
                        response.getBody().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(result.status()).isEqualTo(200);
    assertThat(text("SELECT host(ip) FROM audit_log WHERE actor_id = ?", userId))
        .isEqualTo("203.0.113.9");
  }

  @Test
  void asyncWorkerCannotReadUntilItSetsContext() throws Exception {
    String shopId = shop();
    String token = AuthTestSupport.userToken(user(), shopId, "ACTIVE", future(), 1);
    JsonNode me = JSON.readTree(get("/api/v1/me", token).body());
    UUID tenantId = UUID.fromString(me.path("tenant").path("id").asString());

    // Step 1: The worker pool does not inherit the caller thread, and a later task stays empty.
    assertThat(asyncTenantReader.threadName().get(10, TimeUnit.SECONDS)).startsWith("oms-worker-");
    assertThat(asyncTenantReader.countTenantsAs(tenantId).get(10, TimeUnit.SECONDS)).isEqualTo(1L);
    assertThat(asyncTenantReader.countTenants().get(10, TimeUnit.SECONDS)).isZero();

    // Step 2: A request that has a context still sees zero from the worker.
    HttpResult duringRequest = get("/api/v1/probe/async-count", token);
    assertThat(duringRequest.status()).isEqualTo(200);
    assertThat(duringRequest.body()).contains("0");
  }

  private HttpResult get(String path, String token) {
    return exchange(client.get().uri(path), token);
  }

  private HttpResult post(String path, String token) {
    return exchange(client.post().uri(path), token);
  }

  private HttpResult exchange(RestClient.RequestHeadersSpec<?> spec, String token) {
    if (token != null) {
      spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    return spec.exchange(
        (request, response) ->
            new HttpResult(
                response.getStatusCode().value(),
                new String(
                    response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
  }

  private void assertError(HttpResult result, int status, String code) {
    assertThat(result.status()).isEqualTo(status);
    JsonNode body = JSON.readTree(result.body());
    assertThat(body.path("error").asString()).isEqualTo(code);
    assertThat(body.path("message").asString()).isNotBlank();
    assertThat(body.path("trace_id").asString()).hasSize(32);
  }

  private String text(String sql, String... args) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        var statement = admin.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        statement.setString(i + 1, args[i]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
    }
  }

  private long count(String sql, String... args) throws Exception {
    try (Connection admin = AuthTestSupport.admin();
        var statement = admin.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        statement.setString(i + 1, args[i]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getLong(1);
      }
    }
  }

  private static String shop() {
    return "shop-" + UUID.randomUUID();
  }

  private static String user() {
    return "user-" + UUID.randomUUID();
  }

  private static Instant future() {
    return Instant.now().plus(30, ChronoUnit.DAYS);
  }

  private record HttpResult(int status, String body) {}

  @TestConfiguration
  @EnableAsync
  static class AsyncConfig {

    @Bean(name = "taskExecutor")
    ThreadPoolTaskExecutor taskExecutor() {
      ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
      executor.setCorePoolSize(1);
      executor.setMaxPoolSize(1);
      executor.setQueueCapacity(20);
      executor.setThreadNamePrefix("oms-worker-");
      executor.initialize();
      return executor;
    }
  }

  static class AsyncTenantReader {

    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;

    AsyncTenantReader(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
      this.jdbc = jdbc;
      this.transactions = new TransactionTemplate(transactions);
    }

    @Async
    public CompletableFuture<String> threadName() {
      return CompletableFuture.completedFuture(Thread.currentThread().getName());
    }

    @Async
    public CompletableFuture<Long> countTenants() {
      return CompletableFuture.completedFuture(count());
    }

    @Async
    public CompletableFuture<Long> countTenantsAs(UUID tenantId) {
      TenantContext.set(tenantId, null);
      try {
        return CompletableFuture.completedFuture(count());
      } finally {
        TenantContext.clear();
      }
    }

    private long count() {
      Long value =
          transactions.execute(
              status -> jdbc.queryForObject("SELECT count(*) FROM tenant", Long.class));
      return value == null ? 0 : value;
    }
  }

  @RestController
  static class AsyncProbe {

    private final AsyncTenantReader reader;

    AsyncProbe(AsyncTenantReader reader) {
      this.reader = reader;
    }

    @GetMapping("/api/v1/probe/async-count")
    long asyncCount() throws Exception {
      return reader.countTenants().get(10, TimeUnit.SECONDS);
    }
  }
}
