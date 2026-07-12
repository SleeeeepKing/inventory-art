package com.inventoryart.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResourceLimitsTest {
  @Test
  void productionRejectsTheDevelopmentJwtSecret() {
    AppProperties properties = productionProperties();
    properties.getJwt().setSecret("local-development-secret-change-me-1234567890");

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> new ProductionSafetyValidator(properties).afterPropertiesSet()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JWT_SECRET");
  }

  @Test
  void productionAcceptsExplicitHttpsAndPrivateStorageSettings() {
    AppProperties properties = productionProperties();

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> new ProductionSafetyValidator(properties).afterPropertiesSet()))
        .isNull();
  }

  private static AppProperties productionProperties() {
    AppProperties properties = new AppProperties();
    properties.getJwt().setSecret("unique-production-test-secret-with-more-than-32-bytes");
    properties.getSecurity().setCookieSecure(true);
    properties.getSecurity().setCorsAllowedOrigins("https://app.example.test");
    properties.getStorage().setProvider("r2");
    properties.getStorage().setEndpoint("https://example.r2.cloudflarestorage.com");
    properties.getStorage().setAccessKey("test-access-key");
    properties.getStorage().setSecretKey("test-secret-key");
    properties.getStorage().setBucket("private-test-bucket");
    return properties;
  }
}
