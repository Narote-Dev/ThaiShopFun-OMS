package com.thaishopfun.oms.invariant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(VerifyInvariantsExtension.class)
public @interface VerifyInvariants {

  Scope scope() default Scope.FULL;

  enum Scope {
    /** Stock invariants only (engine tests without sales_order rows). */
    STOCK_ONLY,
    /** Stock + order invariants. */
    FULL
  }
}
