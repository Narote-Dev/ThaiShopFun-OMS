package com.thaishopfun.oms.staging;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Refuses staging boot when demo password hashes are unsafe for production trials. */
final class StagingDemoPasswordPolicy {

  /**
   * bcrypt for placeholder password {@code CHANGE-ME-staging} committed in {@code
   * staging/demo-accounts.json} for structure only.
   */
  static final String COMMITTED_PLACEHOLDER_BCRYPT =
      "$2a$10$YwmX1NsLKBYnSs6S/OUvMOZLVWCHuBFwQsNkh1aY4lRqEluzUCuR.";

  private StagingDemoPasswordPolicy() {}

  static void validate(List<StagingDemoAccount> accounts) {
    Set<String> seenHashes = new HashSet<>();
    for (StagingDemoAccount account : accounts) {
      String hash = account.passwordHash();
      if (COMMITTED_PLACEHOLDER_BCRYPT.equals(hash)) {
        throw new IllegalStateException(
            "staging demo account "
                + account.loginId()
                + " uses the committed placeholder bcrypt; set OMS_STAGING_DEMO_ACCOUNTS with unique per-user hashes");
      }
      if (!seenHashes.add(hash)) {
        throw new IllegalStateException(
            "staging demo accounts must not reuse the same password_hash (set unique bcrypt per user via OMS_STAGING_DEMO_ACCOUNTS)");
      }
    }
  }
}
