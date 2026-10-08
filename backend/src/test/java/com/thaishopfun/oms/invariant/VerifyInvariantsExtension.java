package com.thaishopfun.oms.invariant;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

public class VerifyInvariantsExtension implements BeforeEachCallback, AfterEachCallback {

  @Override
  public void beforeEach(ExtensionContext context) {
    InvariantTestTenants.drain();
  }

  @Override
  public void afterEach(ExtensionContext context) {
    if (skip(context)) {
      InvariantTestTenants.drain();
      return;
    }
    var applicationContext = SpringExtension.getApplicationContext(context);
    if (!applicationContext.containsBean("invariantChecker")) {
      InvariantTestTenants.drain();
      return;
    }
    VerifyInvariants annotation = findAnnotation(context.getRequiredTestClass());
    if (annotation == null) {
      InvariantTestTenants.drain();
      return;
    }
    InvariantChecker checker = applicationContext.getBean(InvariantChecker.class);
    VerifyInvariants.Scope scope = annotation.scope();
    List<Violation> violations = new java.util.ArrayList<>(checker.checkSchema());
    for (UUID tenantId : InvariantTestTenants.drain()) {
      violations.addAll(
          scope == VerifyInvariants.Scope.STOCK_ONLY
              ? checker.checkTenantStock(tenantId)
              : checker.checkTenant(tenantId));
    }
    assertThat(violations).as(InvariantChecker.formatFailures(violations)).isEmpty();
  }

  private static VerifyInvariants findAnnotation(Class<?> type) {
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      VerifyInvariants annotation = current.getAnnotation(VerifyInvariants.class);
      if (annotation != null) {
        return annotation;
      }
    }
    return null;
  }

  private static boolean skip(ExtensionContext context) {
    if (context.getRequiredTestClass().isAnnotationPresent(SkipInvariantCheck.class)) {
      return true;
    }
    Method method = context.getTestMethod().orElse(null);
    return method != null && method.isAnnotationPresent(SkipInvariantCheck.class);
  }
}
