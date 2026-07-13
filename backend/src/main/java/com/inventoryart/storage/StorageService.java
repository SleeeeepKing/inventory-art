package com.inventoryart.storage;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Provider-neutral private object storage. Object keys, never provider URLs, are persisted by the
 * application.
 */
public interface StorageService extends AutoCloseable {
  record PresignedRequest(String url, Map<String, String> headers, Instant expiresAt) {}

  record ObjectMetadata(
      long size, String contentType, String checksumSha256, Map<String, String> userMetadata) {}

  PresignedRequest presignPut(
      String objectKey,
      String contentType,
      long contentLength,
      String checksumSha256,
      Duration validity);

  void put(
      String objectKey,
      InputStream content,
      long contentLength,
      String contentType,
      Map<String, String> metadata)
      throws IOException;

  InputStream get(String objectKey) throws IOException;

  ObjectMetadata head(String objectKey);

  void delete(String objectKey);

  @Override
  default void close() {}
}
