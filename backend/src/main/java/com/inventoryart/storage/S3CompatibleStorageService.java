package com.inventoryart.storage;

import com.inventoryart.exception.BusinessException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/** Used for both local MinIO and Cloudflare R2. */
public final class S3CompatibleStorageService implements StorageService, AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(S3CompatibleStorageService.class);

  private final S3Client client;
  private final S3Presigner presigner;
  private final String bucket;

  public S3CompatibleStorageService(S3Client client, S3Presigner presigner, String bucket) {
    this.client = client;
    this.presigner = presigner;
    this.bucket = bucket;
  }

  @Override
  public PresignedRequest presignPut(
      String objectKey,
      String contentType,
      long contentLength,
      String checksumSha256,
      Duration validity) {
    PutObjectRequest put =
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(objectKey)
            .contentType(contentType)
            .contentLength(contentLength)
            .metadata(Map.of("sha256", checksumSha256))
            .build();
    PresignedPutObjectRequest signed =
        presigner.presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(validity)
                .putObjectRequest(put)
                .build());
    Map<String, String> headers = new LinkedHashMap<>();
    signed
        .signedHeaders()
        .forEach(
            (name, values) -> {
              if (!values.isEmpty() && !name.equalsIgnoreCase("host"))
                headers.put(name, values.getFirst());
            });
    return new PresignedRequest(
        signed.url().toString(), Map.copyOf(headers), Instant.now().plus(validity));
  }

  @Override
  public void put(
      String objectKey,
      InputStream content,
      long contentLength,
      String contentType,
      Map<String, String> metadata) {
    try {
      client.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(objectKey)
              .contentType(contentType)
              .contentLength(contentLength)
              .metadata(metadata)
              .build(),
          RequestBody.fromInputStream(content, contentLength));
    } catch (RuntimeException exception) {
      throw storageFailure("write", exception);
    }
  }

  @Override
  public InputStream get(String objectKey) throws IOException {
    try {
      ResponseInputStream<GetObjectResponse> response =
          client.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
      return response;
    } catch (NoSuchKeyException exception) {
      throw new BusinessException(
          "FILE_NOT_FOUND", "Stored object not found", HttpStatus.NOT_FOUND);
    } catch (RuntimeException exception) {
      throw storageFailure("read", exception);
    }
  }

  @Override
  public ObjectMetadata head(String objectKey) {
    try {
      HeadObjectResponse response =
          client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
      return new ObjectMetadata(
          response.contentLength(),
          response.contentType(),
          response.metadata().get("sha256"),
          response.metadata());
    } catch (NoSuchKeyException exception) {
      throw new BusinessException(
          "FILE_NOT_FOUND", "Stored object not found", HttpStatus.NOT_FOUND);
    } catch (RuntimeException exception) {
      throw storageFailure("inspect", exception);
    }
  }

  @Override
  public void delete(String objectKey) {
    try {
      client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
    } catch (RuntimeException exception) {
      throw storageFailure("delete", exception);
    }
  }

  private BusinessException storageFailure(String operation, RuntimeException cause) {
    if (cause instanceof AwsServiceException serviceException) {
      String errorCode =
          serviceException.awsErrorDetails() == null
              ? null
              : serviceException.awsErrorDetails().errorCode();
      log.error(
          "S3-compatible storage failure: operation={}, status={}, errorCode={}, requestId={}",
          operation,
          serviceException.statusCode(),
          errorCode,
          serviceException.requestId());
    } else {
      log.error(
          "S3-compatible storage failure: operation={}, type={}, message={}",
          operation,
          cause.getClass().getSimpleName(),
          cause.getMessage());
    }
    return new BusinessException(
        "STORAGE_PROVIDER_ERROR",
        "Unable to " + operation + " private object",
        HttpStatus.BAD_GATEWAY);
  }

  @Override
  public void close() {
    presigner.close();
    client.close();
  }
}
