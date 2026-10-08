package com.thaishopfun.oms.invariant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Opt out of {@link VerifyInvariants} for tests that intentionally corrupt data. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SkipInvariantCheck {

  String value();
}
