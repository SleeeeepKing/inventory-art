package com.inventoryart.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceLimitsTest {
    @Test
    void importDefaultsAreSafeForTheSmallRailwayContainer() {
        AppProperties.ImportConfig config = new AppProperties.ImportConfig();

        assertThat(config.getBatchSize()).isEqualTo(200);
        assertThat(config.getMaxRows()).isEqualTo(20_000);
    }

    @Test
    void asyncExecutorHasOneWorkerAndABoundedQueue() {
        var executor = new AsyncConfiguration().applicationTaskExecutor();
        executor.initialize();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(4);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void productionRejectsTheDevelopmentJwtSecret() {
        AppProperties properties = productionProperties();
        properties.getJwt().setSecret("local-development-secret-change-me-1234567890");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
            () -> new ProductionSafetyValidator(properties).afterPropertiesSet()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void productionAcceptsExplicitHttpsAndPrivateStorageSettings() {
        AppProperties properties = productionProperties();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
            () -> new ProductionSafetyValidator(properties).afterPropertiesSet())).isNull();
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
