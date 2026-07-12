package com.inventoryart.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventoryart.exception.BusinessException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3CompatibleStorageServiceTest {
  @Test
  void storageFailureRetainsTheProviderCauseForLogging() {
    S3Client client = mock(S3Client.class);
    S3Presigner presigner = mock(S3Presigner.class);
    RuntimeException providerFailure =
        S3Exception.builder().message("SignatureDoesNotMatch").statusCode(403).build();
    when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(providerFailure);
    S3CompatibleStorageService storage =
        new S3CompatibleStorageService(client, presigner, "inventory-art");
    byte[] content = "image".getBytes(StandardCharsets.UTF_8);

    Throwable failure =
        catchThrowable(
            () ->
                storage.put(
                    "products/image.jpg",
                    new ByteArrayInputStream(content),
                    content.length,
                    "image/jpeg",
                    Map.of("sha256", "checksum")));

    assertThat(failure)
        .isInstanceOf(BusinessException.class)
        .hasMessage("Unable to write private object")
        .hasCause(providerFailure);
  }
}
