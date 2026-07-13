package com.inventoryart.storage;

import com.inventoryart.config.AppProperties;
import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class StorageConfiguration {
  @Bean(destroyMethod = "close")
  StorageService storageService(AppProperties properties) {
    AppProperties.Storage storage = properties.getStorage();
    String provider =
        storage.getProvider() == null ? "local" : storage.getProvider().trim().toLowerCase();
    if (provider.equals("local")) {
      return new LocalStorageService(storage.getLocalPath());
    }
    if (!provider.equals("minio") && !provider.equals("r2") && !provider.equals("s3-compatible")) {
      throw new IllegalStateException("Unsupported storage provider: " + provider);
    }
    StaticCredentialsProvider credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(storage.getAccessKey(), storage.getSecretKey()));
    S3Configuration s3Configuration =
        S3Configuration.builder().pathStyleAccessEnabled(provider.equals("minio")).build();
    S3ClientBuilder clientBuilder =
        S3Client.builder()
            .credentialsProvider(credentials)
            .region(Region.of(storage.getRegion()))
            .serviceConfiguration(s3Configuration);
    S3Presigner.Builder presignerBuilder =
        S3Presigner.builder()
            .credentialsProvider(credentials)
            .region(Region.of(storage.getRegion()))
            .serviceConfiguration(s3Configuration);
    if (storage.getEndpoint() != null && !storage.getEndpoint().isBlank()) {
      URI endpoint = URI.create(storage.getEndpoint());
      clientBuilder.endpointOverride(endpoint);
      URI publicEndpoint =
          storage.getPublicEndpoint() == null || storage.getPublicEndpoint().isBlank()
              ? endpoint
              : URI.create(storage.getPublicEndpoint());
      presignerBuilder.endpointOverride(publicEndpoint);
    }
    return new S3CompatibleStorageService(
        clientBuilder.build(), presignerBuilder.build(), storage.getBucket());
  }
}
