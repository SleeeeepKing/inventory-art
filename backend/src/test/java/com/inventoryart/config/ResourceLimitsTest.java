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
}
