package com.thaishopfun.oms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.thaishopfun.oms.tenant.TenantAwareDataSourceTransactionManager;
import com.thaishopfun.oms.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.hikari.maximum-pool-size=1",
      "spring.datasource.hikari.minimum-idle=1",
      "spring.datasource.hikari.connection-timeout=5000",
      "server.tomcat.threads.max=1",
      "server.tomcat.threads.min-spare=1"
    })
@Import({TenantRlsLeakTest.LeakProbes.class, TenantRlsLeakTest.TenantProbeController.class})
class TenantRlsLeakTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
  }

  @LocalServerPort private int port;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactions;

  private RestClient client;

  @BeforeEach
  void client() {
    client = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
    ThreadLocalEntryFilter.SEEN.clear();
  }

  @Test
  @Timeout(value = 4, unit = TimeUnit.MINUTES)
  void poolOfOneNeverLeaksTenantAcrossRequests() {
    String shopA = "shop-a-" + UUID.randomUUID();
    String shopB = "shop-b-" + UUID.randomUUID();
    String tokenA = AuthTestSupport.userToken(user(), shopA, "ACTIVE", future(), 1);
    String tokenB = AuthTestSupport.userToken(user(), shopB, "ACTIVE", future(), 1);
    assertThat(get("/api/v1/me", tokenA, false).status()).isEqualTo(200);
    assertThat(get("/api/v1/me", tokenB, false).status()).isEqualTo(200);

    for (int i = 0; i < 1000; i++) {
      if (i % 17 == 0) {
        HttpResult boom = get("/api/v1/probe/boom", tokenA, false);
        assertThat(boom.status()).as("iteration %s", i).isEqualTo(500);
      }
      boolean useA = i % 2 == 0;
      HttpResult result = get("/api/v1/probe", useA ? tokenA : tokenB, false);
      assertThat(result.status()).as("iteration %s", i).isEqualTo(200);
      JsonNode body = JSON.readTree(result.body());
      String expected = useA ? shopA : shopB;
      assertThat(body.path("shops")).as("iteration %s", i).hasSize(1);
      assertThat(body.path("shops").get(0).asString()).isEqualTo(expected);
    }
  }

  @Test
  void tenantSettingIsEmptyAfterCommitAndRollbackOnTheSameConnection() {
    assertThat(transactions).isInstanceOf(TenantAwareDataSourceTransactionManager.class);
    String shopId = "shop-tx-" + UUID.randomUUID();
    HttpResult created =
        get("/api/v1/me", AuthTestSupport.userToken(user(), shopId, "ACTIVE", future(), 1), false);
    UUID tenantId =
        UUID.fromString(JSON.readTree(created.body()).path("tenant").path("id").asString());
    TransactionTemplate template = new TransactionTemplate(transactions);

    TenantContext.set(tenantId, null);
    try {
      int[] pid = new int[1];
      template.executeWithoutResult(
          status -> {
            assertThat(setting()).isEqualTo(tenantId.toString());
            assertThat(visibleTenants()).isEqualTo(1);
            pid[0] = backendPid();
          });
      assertThat(setting()).isBlank();
      assertThat(visibleTenants()).isZero();
      assertThat(backendPid()).isEqualTo(pid[0]);

      template.executeWithoutResult(
          status -> {
            assertThat(setting()).isEqualTo(tenantId.toString());
            status.setRollbackOnly();
          });
      assertThat(setting()).isBlank();
      assertThat(visibleTenants()).isZero();
      assertThat(backendPid()).isEqualTo(pid[0]);
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void threadLocalIsClearWhenTheRequestThreadIsReused() {
    String tokenA =
        AuthTestSupport.userToken(user(), "shop-tl-a-" + UUID.randomUUID(), "ACTIVE", future(), 1);
    String tokenB =
        AuthTestSupport.userToken(user(), "shop-tl-b-" + UUID.randomUUID(), "ACTIVE", future(), 1);
    ThreadLocalEntryFilter.SEEN.clear();

    assertThat(get("/api/v1/me", tokenA, true).status()).isEqualTo(200);
    assertThat(get("/api/v1/me", tokenB, true).status()).isEqualTo(200);

    assertThat(ThreadLocalEntryFilter.SEEN).containsExactly("", "");
  }

  private String setting() {
    String value =
        jdbc.queryForObject("SELECT current_setting('app.tenant_id', true)", String.class);
    return value == null ? "" : value;
  }

  private int visibleTenants() {
    Integer count = jdbc.queryForObject("SELECT count(*) FROM tenant", Integer.class);
    return count == null ? 0 : count;
  }

  private int backendPid() {
    Integer pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    return pid == null ? -1 : pid;
  }

  private HttpResult get(String path, String token, boolean probeThread) {
    RestClient.RequestHeadersSpec<?> spec = client.get().uri(path);
    spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    if (probeThread) {
      spec.header("X-Thread-Probe", "1");
    }
    return spec.exchange(
        (request, response) ->
            new HttpResult(
                response.getStatusCode().value(),
                new String(
                    response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
  }

  private static String user() {
    return "user-" + UUID.randomUUID();
  }

  private static Instant future() {
    return Instant.now().plus(30, ChronoUnit.DAYS);
  }

  private record HttpResult(int status, String body) {}

  @TestConfiguration
  static class LeakProbes {

    @Bean
    FilterRegistrationBean<ThreadLocalEntryFilter> threadProbe() {
      FilterRegistrationBean<ThreadLocalEntryFilter> registration =
          new FilterRegistrationBean<>(new ThreadLocalEntryFilter());
      registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
      return registration;
    }
  }

  @RestController
  static class TenantProbeController {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    TenantProbeController(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
      this.jdbc = jdbc;
      this.transactions = new TransactionTemplate(transactions);
    }

    @GetMapping("/api/v1/probe")
    java.util.Map<String, Object> probe() {
      return transactions.execute(
          status -> {
            List<String> shops =
                jdbc.queryForList(
                    "SELECT tsf_shop_id FROM tenant ORDER BY tsf_shop_id", String.class);
            String tenantId =
                jdbc.queryForObject("SELECT current_setting('app.tenant_id', true)", String.class);
            return java.util.Map.of("shops", shops, "tenant_id", tenantId == null ? "" : tenantId);
          });
    }

    @GetMapping("/api/v1/probe/boom")
    ResponseEntity<Void> boom() {
      // Step 1: Open a tenant transaction, then fail. The template rolls it back.
      // Step 2: Return 500 here so the container does not log a stack for every iteration.
      try {
        transactions.executeWithoutResult(
            status -> {
              jdbc.queryForList("SELECT tsf_shop_id FROM tenant", String.class);
              throw new IllegalStateException("boom");
            });
      } catch (IllegalStateException ex) {
        return ResponseEntity.internalServerError().build();
      }
      return ResponseEntity.internalServerError().build();
    }
  }

  static class ThreadLocalEntryFilter extends OncePerRequestFilter {

    static final List<String> SEEN = new CopyOnWriteArrayList<>();

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
      if (request.getHeader("X-Thread-Probe") != null) {
        UUID tenantId = TenantContext.tenantId();
        SEEN.add(tenantId == null ? "" : tenantId.toString());
      }
      filterChain.doFilter(request, response);
    }
  }
}
