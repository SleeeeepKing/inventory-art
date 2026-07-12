package com.inventoryart.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

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

  @Test
  void r2DisablesOptionalSdkChecksums() {
    S3ClientBuilder builder = mock(S3ClientBuilder.class);

    StorageConfiguration.configureClientCompatibility(builder, "r2");

    verify(builder).requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED);
    verify(builder).responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
  }

  @Test
  void minioKeepsItsNativeChecksumBehavior() {
    S3ClientBuilder builder = mock(S3ClientBuilder.class);

    StorageConfiguration.configureClientCompatibility(builder, "minio");

    verifyNoInteractions(builder);
  }

  @Test
  void r2PutObjectUsesAFixedLengthUnsignedBodyWithoutOptionalChecksumHeaders() throws Exception {
    AtomicReference<com.sun.net.httpserver.Headers> capturedHeaders = new AtomicReference<>();
    AtomicReference<byte[]> capturedBody = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          capturedHeaders.set(exchange.getRequestHeaders());
          capturedBody.set(exchange.getRequestBody().readAllBytes());
          exchange.getResponseHeaders().add("ETag", "\"test-etag\"");
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    byte[] content = "r2-compatible-body".getBytes(StandardCharsets.UTF_8);

    S3ClientBuilder builder =
        S3Client.builder()
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("access", "secret")))
            .region(Region.of("auto"))
            .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
            .serviceConfiguration(StorageConfiguration.s3Configuration("r2"));
    StorageConfiguration.configureClientCompatibility(builder, "r2");

    try (S3Client client = builder.build()) {
      client.putObject(
          PutObjectRequest.builder().bucket("inventory-art").key("image.jpg").build(),
          RequestBody.fromInputStream(new ByteArrayInputStream(content), content.length));
    } finally {
      server.stop(0);
    }

    assertThat(capturedBody.get()).isEqualTo(content);
    assertThat(capturedHeaders.get().getFirst("Content-length"))
        .isEqualTo(Integer.toString(content.length));
    assertThat(capturedHeaders.get().getOrDefault("Transfer-encoding", List.of())).isEmpty();
    assertThat(capturedHeaders.get().getOrDefault("x-amz-sdk-checksum-algorithm", List.of()))
        .isEmpty();
    assertThat(capturedHeaders.get().getOrDefault("x-amz-checksum-crc32", List.of())).isEmpty();
  }
}
