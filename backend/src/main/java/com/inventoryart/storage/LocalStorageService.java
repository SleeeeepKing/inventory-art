package com.inventoryart.storage;

import com.inventoryart.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.DigestInputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class LocalStorageService implements StorageService {
    private final Path root;
    private final byte[] signingKey = new byte[32];

    public LocalStorageService(String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        new SecureRandom().nextBytes(signingKey);
        try {
            Files.createDirectories(this.root);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create local storage directory", exception);
        }
    }

    @Override
    public PresignedRequest presignPut(String objectKey, String contentType, long contentLength,
                                       String checksumSha256, Duration validity) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        headers.put("X-Content-Sha256", checksumSha256);
        Instant expires = Instant.now().plus(validity);
        return new PresignedRequest(localUrl(objectKey, "PUT", expires), Map.copyOf(headers), expires);
    }

    @Override
    public PresignedRequest presignGet(String objectKey, Duration validity) {
        Instant expires = Instant.now().plus(validity);
        return new PresignedRequest(localUrl(objectKey, "GET", expires), Map.of(), expires);
    }

    private String localUrl(String objectKey, String operation, Instant expires) {
        long epoch = expires.getEpochSecond();
        return "/api/v1/files/local?objectKey=" + URLEncoder.encode(objectKey, StandardCharsets.UTF_8)
            + "&expires=" + epoch + "&signature=" + signature(objectKey, operation, epoch);
    }

    void verifyPresigned(String objectKey, String operation, long expires, String suppliedSignature) {
        if (expires < Instant.now().getEpochSecond() || suppliedSignature == null
            || !MessageDigest.isEqual(signature(objectKey, operation, expires).getBytes(StandardCharsets.US_ASCII),
                suppliedSignature.getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException("INVALID_PRESIGNED_URL", "Presigned local storage URL is invalid or expired",
                HttpStatus.FORBIDDEN);
        }
    }

    private String signature(String objectKey, String operation, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((operation + "\n" + objectKey + "\n" + expires)
                .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public void put(String objectKey, InputStream content, long contentLength, String contentType,
                    Map<String, String> metadata) throws IOException {
        Path destination = resolve(objectKey);
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), ".upload-", ".tmp");
        long copied;
        try (OutputStream output = Files.newOutputStream(temporary)) {
            copied = content.transferTo(output);
        } catch (IOException exception) {
            Files.deleteIfExists(temporary);
            throw exception;
        }
        if (contentLength >= 0 && copied != contentLength) {
            Files.deleteIfExists(temporary);
            throw new BusinessException("FILE_SIZE_MISMATCH", "Uploaded object size does not match the request");
        }
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public InputStream get(String objectKey) throws IOException {
        Path path = resolve(objectKey);
        if (!Files.isRegularFile(path)) {
            throw new BusinessException("FILE_NOT_FOUND", "Stored object not found", HttpStatus.NOT_FOUND);
        }
        return new BufferedInputStream(Files.newInputStream(path));
    }

    @Override
    public ObjectMetadata head(String objectKey) {
        Path path = resolve(objectKey);
        if (!Files.isRegularFile(path)) {
            throw new BusinessException("FILE_NOT_FOUND", "Stored object not found", HttpStatus.NOT_FOUND);
        }
        try {
            String contentType = Files.probeContentType(path);
            return new ObjectMetadata(Files.size(path), contentType == null ? "application/octet-stream" : contentType,
                sha256(path), Map.of());
        } catch (IOException exception) {
            throw new BusinessException("STORAGE_READ_FAILED", "Unable to inspect stored object", HttpStatus.BAD_GATEWAY);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException exception) {
            throw new BusinessException("STORAGE_DELETE_FAILED", "Unable to delete stored object", HttpStatus.BAD_GATEWAY);
        }
    }

    private Path resolve(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.startsWith("/")) {
            throw new BusinessException("INVALID_OBJECT_KEY", "Invalid storage object key");
        }
        Path path = root.resolve(objectKey).normalize();
        if (!path.startsWith(root)) {
            throw new BusinessException("INVALID_OBJECT_KEY", "Storage path traversal is not allowed");
        }
        return path;
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
