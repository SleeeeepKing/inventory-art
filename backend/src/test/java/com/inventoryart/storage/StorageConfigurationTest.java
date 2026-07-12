package com.inventoryart.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Configuration;

class StorageConfigurationTest {
  @Test
  void r2UsesCloudflareCompatiblePutObjectSettings() {
    S3Configuration configuration = StorageConfiguration.s3Configuration("r2");

    assertThat(configuration.pathStyleAccessEnabled()).isTrue();
    assertThat(configuration.chunkedEncodingEnabled()).isFalse();
  }

  @Test
  void minioKeepsChunkedPathStyleRequests() {
    S3Configuration configuration = StorageConfiguration.s3Configuration("minio");

    assertThat(configuration.pathStyleAccessEnabled()).isTrue();
    assertThat(configuration.chunkedEncodingEnabled()).isTrue();
  }
}
