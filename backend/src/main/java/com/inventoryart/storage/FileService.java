package com.inventoryart.storage;

import com.inventoryart.audit.AuditService;
import com.inventoryart.config.AppProperties;
import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.security.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

@Service
public class FileService {
    private static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final StoredFileRepository repository;
    private final StorageService storage;
    private final CurrentUserService currentUser;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Duration presignValidity;

    public FileService(StoredFileRepository repository, StorageService storage, CurrentUserService currentUser,
                       JdbcTemplate jdbc, AuditService audit,
                       AppProperties properties) {
        this.repository = repository;
        this.storage = storage;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
        this.audit = audit;
        this.presignValidity = Duration.ofSeconds(properties.getStorage().getPresignedExpirationSeconds());
    }

    @Transactional
    public FileDtos.PresignUploadResponse presignProductImage(FileDtos.PresignUploadRequest request) {
        validateImage(request.contentType(), request.size());
        UUID tenantId = currentUser.tenantId();
        Integer productExists = jdbc.queryForObject(
            "select count(*) from products where tenant_id = ? and id = ?", Integer.class, tenantId, request.productId());
        if (productExists == null || productExists == 0) throw new NotFoundException("Product");
        String checksum = request.checksumSha256().toLowerCase(Locale.ROOT);
        String extension = extension(request.originalFilename(), request.contentType());
        String key = "tenants/%s/products/%s/%s%s".formatted(tenantId, request.productId(), UUID.randomUUID(), extension);
        StoredFile file = repository.save(StoredFile.pending(tenantId, key, safeFilename(request.originalFilename()),
            request.contentType(), request.size(), checksum, StoredFile.Purpose.PRODUCT_IMAGE, "PRODUCT",
            request.productId(), currentUser.userId()));
        StorageService.PresignedRequest signed = storage.presignPut(key, request.contentType(), request.size(), checksum,
            presignValidity);
        return new FileDtos.PresignUploadResponse(file.getId(), key, signed.url(), signed.headers(), signed.expiresAt());
    }

    @Transactional
    public FileDtos.ConfirmFileResponse confirm(UUID fileId) {
        StoredFile file = required(fileId);
        if (file.getStatus() == StoredFile.Status.CONFIRMED) {
            return new FileDtos.ConfirmFileResponse(file.getId(), file.getStatus().name(), file.getConfirmedAt());
        }
        if (file.getStatus() != StoredFile.Status.PENDING) {
            throw new BusinessException("FILE_NOT_PENDING", "Only pending files can be confirmed", HttpStatus.CONFLICT);
        }
        StorageService.ObjectMetadata actual = storage.head(file.getObjectKey());
        if (actual.size() != file.getSize()) {
            throw new BusinessException("FILE_SIZE_MISMATCH", "Uploaded file size does not match the presigned request");
        }
        if (actual.contentType() == null || !actual.contentType().equalsIgnoreCase(file.getContentType())) {
            throw new BusinessException("FILE_CONTENT_TYPE_MISMATCH", "Uploaded file type does not match the presigned request");
        }
        if (!actualChecksum(file.getObjectKey()).equalsIgnoreCase(file.getChecksum())) {
            throw new BusinessException("FILE_CHECKSUM_MISMATCH", "Uploaded file checksum could not be verified");
        }
        Instant now = Instant.now();
        file.confirm(now);
        if (file.getPurpose() == StoredFile.Purpose.PRODUCT_IMAGE && file.getResourceId() != null) {
            String previousKey = jdbc.query("select image_object_key from products where tenant_id=? and id=?",
                result -> result.next() ? result.getString(1) : null, file.getTenantId(), file.getResourceId());
            int updated = jdbc.update("""
                update products set image_object_key = ?, updated_at = ?
                where tenant_id = ? and id = ?
                """, file.getObjectKey(), now, file.getTenantId(), file.getResourceId());
            if (updated != 1) throw new NotFoundException("Product");
            if (previousKey != null && !previousKey.equals(file.getObjectKey())) {
                repository.findByObjectKeyAndTenantId(previousKey, file.getTenantId()).ifPresent(previous -> {
                    previous.deleted(now);
                });
                deleteObjectAfterCommit(previousKey);
            }
        }
        audit.record(file.getTenantId(), "FILE_CONFIRM", "STORED_FILE", fileId, "SUCCESS",
            Map.of("purpose", file.getPurpose().name()));
        return new FileDtos.ConfirmFileResponse(file.getId(), file.getStatus().name(), now);
    }

    @Transactional(readOnly = true)
    public FileDtos.DownloadUrlResponse downloadUrl(UUID fileId) {
        StoredFile file = required(fileId);
        if (file.getStatus() != StoredFile.Status.CONFIRMED) {
            throw new BusinessException("FILE_NOT_AVAILABLE", "File is not available", HttpStatus.CONFLICT);
        }
        StorageService.PresignedRequest signed = storage.presignGet(file.getObjectKey(), presignValidity);
        return new FileDtos.DownloadUrlResponse(signed.url(), signed.expiresAt());
    }

    @Transactional(readOnly = true)
    public String productImageUrl(UUID productId, String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return null;
        StoredFile file = repository.findByObjectKeyAndTenantId(objectKey, currentUser.tenantId())
            .filter(candidate -> candidate.getStatus() == StoredFile.Status.CONFIRMED)
            .filter(candidate -> productId.equals(candidate.getResourceId()))
            .orElse(null);
        if (file == null) return null;
        String url = storage.presignGet(file.getObjectKey(), presignValidity).url();
        if (!url.startsWith("/")) return url;
        URI relative = URI.create(url);
        return ServletUriComponentsBuilder.fromCurrentContextPath().path(relative.getPath())
            .query(relative.getRawQuery()).build(true).toUriString();
    }

    @Transactional
    public void delete(UUID fileId) {
        StoredFile file = required(fileId);
        if (file.getStatus() == StoredFile.Status.DELETED) return;
        jdbc.update("update products set image_object_key=null, updated_at=now() where tenant_id=? and image_object_key=?",
            file.getTenantId(), file.getObjectKey());
        file.deleted(Instant.now());
        deleteObjectAfterCommit(file.getObjectKey());
        audit.record(file.getTenantId(), "FILE_DELETE", "STORED_FILE", fileId, "SUCCESS",
            Map.of("purpose", file.getPurpose().name()));
    }

    @Transactional(readOnly = true)
    StoredFile requiredByObjectKey(String key) {
        return repository.findByObjectKeyAndTenantId(key, currentUser.tenantId())
            .orElseThrow(() -> new NotFoundException("File"));
    }

    void receiveLocalUpload(String objectKey, long expires, String signature, InputStream input,
                            long contentLength, String contentType, String checksum) throws IOException {
        if (!(storage instanceof LocalStorageService local)) {
            throw new BusinessException("LOCAL_STORAGE_DISABLED", "Local upload endpoint is disabled", HttpStatus.NOT_FOUND);
        }
        local.verifyPresigned(objectKey, "PUT", expires, signature);
        StoredFile file = repository.findByObjectKey(objectKey).orElseThrow(() -> new NotFoundException("File"));
        if (file.getStatus() != StoredFile.Status.PENDING) {
            throw new BusinessException("FILE_NOT_PENDING", "Only pending files can be uploaded", HttpStatus.CONFLICT);
        }
        if (contentLength != file.getSize() || !file.getContentType().equalsIgnoreCase(contentType)
            || checksum == null || !file.getChecksum().equalsIgnoreCase(checksum)) {
            throw new BusinessException("UPLOAD_CONSTRAINT_MISMATCH", "Upload headers do not match the presigned request");
        }
        storage.put(objectKey, input, contentLength, contentType, Map.of("sha256", checksum));
    }

    StoredFile localDownloadFile(String objectKey, long expires, String signature) {
        if (!(storage instanceof LocalStorageService local)) {
            throw new BusinessException("LOCAL_STORAGE_DISABLED", "Local download endpoint is disabled", HttpStatus.NOT_FOUND);
        }
        local.verifyPresigned(objectKey, "GET", expires, signature);
        StoredFile file = repository.findByObjectKey(objectKey).orElseThrow(() -> new NotFoundException("File"));
        if (file.getStatus() != StoredFile.Status.CONFIRMED) throw new NotFoundException("File");
        return file;
    }

    InputStream openLocalDownload(StoredFile file) throws IOException {
        return storage.get(file.getObjectKey());
    }

    private StoredFile required(UUID id) {
        return repository.findByIdAndTenantId(id, currentUser.tenantId()).orElseThrow(() -> new NotFoundException("File"));
    }

    private static void validateImage(String contentType, long size) {
        if (!IMAGE_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new BusinessException("UNSUPPORTED_IMAGE_TYPE", "Only JPG, PNG and WebP images are supported");
        }
        if (size <= 0 || size > MAX_IMAGE_SIZE) {
            throw new BusinessException("IMAGE_TOO_LARGE", "Image must be no larger than 10 MB");
        }
    }

    private String actualChecksum(String objectKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(storage.get(objectKey), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new BusinessException("STORAGE_READ_FAILED", "Unable to verify uploaded file", HttpStatus.BAD_GATEWAY);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String safeFilename(String name) {
        String leaf = name == null ? "image" : name.replace('\\', '/');
        leaf = leaf.substring(leaf.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        return leaf.isBlank() ? "image" : leaf.substring(0, Math.min(leaf.length(), 500));
    }

    private void deleteObjectAfterCommit(String objectKey) {
        Runnable deletion = () -> {
            try { storage.delete(objectKey); } catch (RuntimeException ignored) { /* orphan cleanup can retry */ }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { deletion.run(); }
            });
        } else {
            deletion.run();
        }
    }

    private static String extension(String filename, String contentType) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (contentType.equalsIgnoreCase("image/jpeg") && (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
            return lower.endsWith(".jpeg") ? ".jpeg" : ".jpg";
        }
        if (contentType.equalsIgnoreCase("image/png") && lower.endsWith(".png")) return ".png";
        if (contentType.equalsIgnoreCase("image/webp") && lower.endsWith(".webp")) return ".webp";
        throw new BusinessException("IMAGE_EXTENSION_MISMATCH", "Image extension does not match its content type");
    }
}
