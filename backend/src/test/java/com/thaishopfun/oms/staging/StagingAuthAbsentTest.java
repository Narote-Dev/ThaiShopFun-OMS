package com.thaishopfun.oms.staging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

class StagingAuthAbsentTest {

  @Test
  void stagingAuthConfigurationIsGuardedByStagingProfile() {
    Profile profile = StagingAuthConfiguration.class.getAnnotation(Profile.class);
    assertThat(profile).isNotNull();
    assertThat(profile.value()).containsExactly("staging");
  }

  @Test
  void stagingIdpControllerIsGuardedByStagingProfile() throws Exception {
    Class<?> controller = Class.forName("com.thaishopfun.oms.staging.StagingIdpController");
    Profile profile = controller.getAnnotation(Profile.class);
    assertThat(profile).isNotNull();
    assertThat(profile.value()).containsExactly("staging");
  }
}
