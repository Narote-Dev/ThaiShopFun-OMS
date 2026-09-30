package com.thaishopfun.oms.catalog;

import com.thaishopfun.oms.auth.AuthTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * One cached application context for every T07 integration test. The Postgres container is shared
 * by the whole suite, so each extra context (and its pool) costs connection slots.
 */
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "oms.outbox.publisher-enabled=false")
public abstract class CatalogIntegrationTest {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    AuthTestSupport.register(registry);
  }

  @LocalServerPort private int port;

  protected CatalogHttp http;

  @BeforeEach
  void client() {
    http = new CatalogHttp(port);
  }
}
