package com.inventoryart.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ResourceLimitsTest {
  @Test
  void productionRejectsTheDevelopmentJwtSecret() {
    AppProperties properties = productionProperties();
    properties.getJwt().setSecret("local-development-secret-change-me-1234567890");

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> productionValidator(properties).afterPropertiesSet()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JWT_SECRET");
  }

  @Test
  void productionAcceptsExplicitHttpsAndPrivateStorageSettings() {
    AppProperties properties = productionProperties();

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> productionValidator(properties).afterPropertiesSet()))
        .isNull();
  }

  @Test
  void productionRejectsLocalHttpByDefault() {
    AppProperties properties = productionProperties();
    properties.getSecurity().setCorsAllowedOrigins("http://localhost:5173");

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> productionValidator(properties).afterPropertiesSet()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CORS_ALLOWED_ORIGINS");
  }

  @Test
  void localProductionDebugAcceptsExactLoopbackOriginsAndInsecureCookie() {
    AppProperties properties = productionProperties();
    properties.getSecurity().setCookieSecure(false);
    properties.getSecurity().setCorsAllowedOrigins("http://localhost:5173,http://127.0.0.1:4173");

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> localProductionValidator(properties).afterPropertiesSet()))
        .isNull();
  }

  @Test
  void localProductionDebugStillRejectsNonLoopbackHttpOrigins() {
    AppProperties properties = productionProperties();
    properties.getSecurity().setCookieSecure(false);
    properties.getSecurity().setCorsAllowedOrigins("http://debug.example.test");

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> localProductionValidator(properties).afterPropertiesSet()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CORS_ALLOWED_ORIGINS");
  }

  @Test
  void localProductionDebugRejectsLoopbackOriginsWithPaths() {
    AppProperties properties = productionProperties();
    properties.getSecurity().setCookieSecure(false);
    properties.getSecurity().setCorsAllowedOrigins("https://localhost:5173/not-an-origin");

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> localProductionValidator(properties).afterPropertiesSet()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CORS_ALLOWED_ORIGINS");
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

  private static ProductionSafetyValidator productionValidator(AppProperties properties) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    return new ProductionSafetyValidator(properties, environment);
  }

  private static ProductionSafetyValidator localProductionValidator(AppProperties properties) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod", "local-prod-debug");
    return new ProductionSafetyValidator(properties, environment);
  }
}
