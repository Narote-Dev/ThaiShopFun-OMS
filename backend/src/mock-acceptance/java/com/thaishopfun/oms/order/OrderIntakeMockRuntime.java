package com.thaishopfun.oms.order;

import com.thaishopfun.mocktsf.MockTsfApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Single in-process mock-tsf for T12 acceptance tests (one JWKS, one port). */
final class OrderIntakeMockRuntime {

  private static final String ISSUER = "http://mock-tsf.test/tsf-idp";
  private static ConfigurableApplicationContext mock;

  private OrderIntakeMockRuntime() {}

  static String issuer() {
    return ISSUER;
  }

  static synchronized void startMock() {
    if (mock != null) {
      return;
    }
    SpringApplication app = MockTsfApplication.application();
    mock =
        app.run(
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--mock.issuer=" + ISSUER,
            "--mock.oms-base-url=http://127.0.0.1:9",
            "--spring.main.banner-mode=off",
            "--spring.main.register-shutdown-hook=false");
  }

  static ConfigurableApplicationContext mock() {
    startMock();
    return mock;
  }

  static int mockPort() {
    String port = mock.getEnvironment().getProperty("local.server.port");
    if (port == null || port.isBlank() || "0".equals(port)) {
      throw new IllegalStateException("mock-tsf did not bind a port");
    }
    return Integer.parseInt(port);
  }

  static synchronized void stopMock() {
    if (mock != null) {
      mock.close();
      mock = null;
    }
  }
}
