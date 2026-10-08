package com.thaishopfun.oms.invariant;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

public class VerifyInvariantsExtension implements AfterEachCallback {

  @Override
  public void afterEach(ExtensionContext context) {
    if (skip(context)) {
      return;
    }
    var applicationContext = SpringExtension.getApplicationContext(context);
    if (!applicationContext.containsBean("invariantChecker")) {
      return;
    }
    InvariantChecker checker = applicationContext.getBean(InvariantChecker.class);
    List<Violation> violations = checker.checkAll();
    assertThat(violations).as(InvariantChecker.formatFailures(violations)).isEmpty();
  }

  private static boolean skip(ExtensionContext context) {
    if (context.getRequiredTestClass().isAnnotationPresent(SkipInvariantCheck.class)) {
      return true;
    }
    Method method = context.getTestMethod().orElse(null);
    return method != null && method.isAnnotationPresent(SkipInvariantCheck.class);
  }
}
