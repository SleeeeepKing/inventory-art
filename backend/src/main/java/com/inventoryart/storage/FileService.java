package com.inventoryart.storage;

import com.inventoryart.audit.AuditService;
import com.inventoryart.config.AppProperties;
import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.security.CurrentUserService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class FileService {
  private static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;
  private static final long MAX_PREVIEW_SIZE = 512L * 1024;
  private static final String PREVIEW_CONTENT_TYPE = "image/webp";
  private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
  private static final Set<String> LEGACY_PREVIEW_TYPES = Set.of("image/jpeg", "image/png");

  private final StoredFileRepository repository;
  private final StorageService storage;
  private final CurrentUserService currentUser;
  private final JdbcTemplate jdbc;
  private final AuditService audit;
  private final Duration presignValidity;

  public FileService(
      StoredFileRepository repository,
      StorageService storage,
      CurrentUserService currentUser,
      JdbcTemplate jdbc,
      AuditService audit,
      AppProperties properties) {
    this.repository = repository;
    this.storage = storage;
    this.currentUser = currentUser;
    this.jdbc = jdbc;
    this.audit = audit;
    this.presignValidity =
        Duration.ofSeconds(properties.getStorage().getPresignedExpirationSeconds());
  }

  @Transactional
  public FileDtos.PresignUploadResponse presignProductImage(FileDtos.PresignUploadRequest request) {
    validateImage(request.contentType(), request.size());
    validatePreviewRequest(request.previewSize());
    UUID tenantId = currentUser.tenantId();
    if ((request.productId() == null) == (request.productFamilyId() == null)) {
      throw new BusinessException(
          "INVALID_IMAGE_TARGET", "Exactly one product or product family is required");
    }
    StorageIdentity identity = storageIdentity(tenantId, request);
    String checksum = request.checksumSha256().toLowerCase(Locale.ROOT);
    String previewChecksum = request.previewChecksumSha256().toLowerCase(Locale.ROOT);
    String extension = extension(request.originalFilename(), request.contentType());
    String assetRoot =
        "tenants/%s/%s/%s/%s"
            .formatted(
                identity.tenantSlug(),
                identity.resourceType(),
                keySegment(identity.resourceKey()),
                UUID.randomUUID());
    String key = assetRoot + "/original" + extension;
    String previewKey = assetRoot + "/preview.webp";
    StoredFile file =
        repository.save(
            StoredFile.pending(
                tenantId,
                key,
                previewKey,
                safeFilename(request.originalFilename()),
                request.contentType(),
                request.size(),
                checksum,
                PREVIEW_CONTENT_TYPE,
                request.previewSize(),
                previewChecksum,
                request.productId(),
                identity.productFamilyId(),
                currentUser.userId()));
    StorageService.PresignedRequest signed =
        storage.presignPut(key, request.contentType(), request.size(), checksum, presignValidity);
    StorageService.PresignedRequest previewSigned =
        storage.presignPut(
            previewKey,
            PREVIEW_CONTENT_TYPE,
            request.previewSize(),
            previewChecksum,
            presignValidity);
    return new FileDtos.PresignUploadResponse(
        file.getId(),
        key,
        signed.url(),
        signed.headers(),
        previewSigned.url(),
        previewSigned.headers(),
        signed.expiresAt());
  }

  @Transactional
  public FileDtos.ConfirmFileResponse confirm(UUID fileId) {
    StoredFile file = required(fileId);
    if (file.getStatus() == StoredFile.Status.CONFIRMED) {
      return new FileDtos.ConfirmFileResponse(
          file.getId(), file.getStatus().name(), file.getConfirmedAt());
    }
    if (file.getStatus() != StoredFile.Status.PENDING) {
      throw new BusinessException(
          "FILE_NOT_PENDING", "Only pending files can be confirmed", HttpStatus.CONFLICT);
    }
    validateStoredObject(
        file.getObjectKey(), file.getContentType(), file.getSize(), file.getChecksum(), "FILE");
    validateStoredObject(
        file.getPreviewObjectKey(),
        file.getPreviewContentType(),
        file.getPreviewSize(),
        file.getPreviewChecksum(),
        "PREVIEW");
    validatePreviewDimensions(file.getPreviewObjectKey());
    Instant now = Instant.now();
    file.confirm(now);
    if (file.getProductFamilyId() != null) {
      confirmFamilyImage(file, now);
    } else if (file.getProductId() != null) {
      String previousKey =
          jdbc.query(
              "select image_object_key from products where tenant_id=? and id=?",
              result -> result.next() ? result.getString(1) : null,
              file.getTenantId(),
              file.getProductId());
      int updated =
          jdbc.update(
              """
                update products set image_object_key = ?, updated_at = ?
                where tenant_id = ? and id = ?
                """,
              file.getObjectKey(),
              Timestamp.from(now),
              file.getTenantId(),
              file.getProductId());
      if (updated != 1) throw new NotFoundException("Product");
      if (previousKey != null && !previousKey.equals(file.getObjectKey())) {
        repository
            .findByObjectKeyAndTenantId(previousKey, file.getTenantId())
            .ifPresent(
                previous -> {
                  previous.deleted(now);
                  deleteObjectsAfterCommit(previous.getObjectKey(), previous.getPreviewObjectKey());
                });
      }
    }
    audit.record(
        file.getTenantId(),
        "FILE_CONFIRM",
        "STORED_FILE",
        fileId,
        "SUCCESS",
        imageAuditDetails(file));
    return new FileDtos.ConfirmFileResponse(file.getId(), file.getStatus().name(), now);
  }

  @Transactional(readOnly = true)
  public String productImageUrl(UUID productId, String objectKey) {
    if (objectKey == null || objectKey.isBlank()) return null;
    StoredFile file =
        repository
            .findByObjectKeyAndTenantId(objectKey, currentUser.tenantId())
            .filter(candidate -> candidate.getStatus() == StoredFile.Status.CONFIRMED)
            .filter(candidate -> productId.equals(candidate.getProductId()))
            .orElse(null);
    if (file == null) return null;
    if (file.getPreviewObjectKey() == null
        && !LEGACY_PREVIEW_TYPES.contains(file.getContentType().toLowerCase(Locale.ROOT))) {
      return null;
    }
    return "/files/%s/preview".formatted(file.getId());
  }

  @Transactional(readOnly = true)
  public String productFamilyImageUrl(UUID productFamilyId, String objectKey) {
    if (objectKey == null || objectKey.isBlank()) return null;
    StoredFile file =
        repository
            .findByObjectKeyAndTenantId(objectKey, currentUser.tenantId())
            .filter(candidate -> candidate.getStatus() == StoredFile.Status.CONFIRMED)
            .filter(candidate -> productFamilyId.equals(candidate.getProductFamilyId()))
            .orElse(null);
    if (file == null) return null;
    if (file.getPreviewObjectKey() == null
        && !LEGACY_PREVIEW_TYPES.contains(file.getContentType().toLowerCase(Locale.ROOT))) {
      return null;
    }
    return "/files/%s/preview".formatted(file.getId());
  }

  @Transactional(readOnly = true)
  public String catalogImageUrl(UUID productId, UUID productFamilyId, String objectKey) {
    return productFamilyId == null
        ? productImageUrl(productId, objectKey)
        : productFamilyImageUrl(productFamilyId, objectKey);
  }

  @Transactional(readOnly = true)
  public PreviewContent preview(UUID fileId) throws IOException {
    StoredFile file = required(fileId);
    if (file.getStatus() != StoredFile.Status.CONFIRMED) {
      throw new BusinessException(
          "FILE_NOT_AVAILABLE", "File is not available", HttpStatus.CONFLICT);
    }
    if (file.getPreviewObjectKey() != null) {
      return new PreviewContent(
          file.getPreviewContentType(),
          file.getPreviewSize(),
          storage.get(file.getPreviewObjectKey()));
    }
    if (!LEGACY_PREVIEW_TYPES.contains(file.getContentType().toLowerCase(Locale.ROOT))) {
      throw new NotFoundException("Image preview");
    }
    byte[] preview;
    try (InputStream original = storage.get(file.getObjectKey())) {
      preview = LegacyImagePreviewer.jpeg(original);
    }
    return new PreviewContent(
        "image/jpeg", (long) preview.length, new ByteArrayInputStream(preview));
  }

  @Transactional
  public void delete(UUID fileId) {
    StoredFile file = required(fileId);
    if (file.getStatus() == StoredFile.Status.DELETED) return;
    jdbc.update(
        "update products set image_object_key=null, updated_at=now() where tenant_id=? and image_object_key=?",
        file.getTenantId(),
        file.getObjectKey());
    jdbc.update(
        "update product_families set image_object_key=null, updated_at=now() where tenant_id=? and image_object_key=?",
        file.getTenantId(),
        file.getObjectKey());
    file.deleted(Instant.now());
    deleteObjectsAfterCommit(file.getObjectKey(), file.getPreviewObjectKey());
    audit.record(
        file.getTenantId(),
        "FILE_DELETE",
        "STORED_FILE",
        fileId,
        "SUCCESS",
        imageAuditDetails(file));
  }

  void receiveLocalUpload(
      String objectKey,
      long expires,
      String signature,
      InputStream input,
      long contentLength,
      String contentType,
      String checksum)
      throws IOException {
    if (!(storage instanceof LocalStorageService local)) {
      throw new BusinessException(
          "LOCAL_STORAGE_DISABLED", "Local upload endpoint is disabled", HttpStatus.NOT_FOUND);
    }
    local.verifyPresigned(objectKey, "PUT", expires, signature);
    StoredFile file =
        repository
            .findByObjectKeyOrPreviewObjectKey(objectKey, objectKey)
            .orElseThrow(() -> new NotFoundException("File"));
    if (file.getStatus() != StoredFile.Status.PENDING) {
      throw new BusinessException(
          "FILE_NOT_PENDING", "Only pending files can be uploaded", HttpStatus.CONFLICT);
    }
    boolean preview = objectKey.equals(file.getPreviewObjectKey());
    long expectedSize = preview ? file.getPreviewSize() : file.getSize();
    String expectedContentType = preview ? file.getPreviewContentType() : file.getContentType();
    String expectedChecksum = preview ? file.getPreviewChecksum() : file.getChecksum();
    if (contentLength != expectedSize
        || !sameContentType(expectedContentType, contentType)
        || checksum == null
        || !expectedChecksum.equalsIgnoreCase(checksum)) {
      throw new BusinessException(
          "UPLOAD_CONSTRAINT_MISMATCH", "Upload headers do not match the presigned request");
    }
    storage.put(objectKey, input, contentLength, expectedContentType, Map.of("sha256", checksum));
  }

  private StoredFile required(UUID id) {
    return repository
        .findByIdAndTenantId(id, currentUser.tenantId())
        .orElseThrow(() -> new NotFoundException("File"));
  }

  private StorageIdentity storageIdentity(UUID tenantId, FileDtos.PresignUploadRequest request) {
    if (request.productFamilyId() != null) {
      return jdbc
          .query(
              """
                select t.slug, f.id
                from product_families f
                join tenants t on t.id=f.tenant_id
                where f.tenant_id=? and f.id=?
                """,
              (result, row) ->
                  new StorageIdentity(
                      result.getString(1),
                      "product-families",
                      result.getObject(2, UUID.class).toString(),
                      request.productFamilyId()),
              tenantId,
              request.productFamilyId())
          .stream()
          .findFirst()
          .orElseThrow(() -> new NotFoundException("Product family"));
    }
    return jdbc
        .query(
            """
              select t.slug, p.sku, p.family_id
              from products p
              join tenants t on t.id=p.tenant_id
              where p.tenant_id=? and p.id=?
              """,
            (result, row) ->
                new StorageIdentity(
                    result.getString(1),
                    "products",
                    result.getString(2),
                    result.getObject(3, UUID.class)),
            tenantId,
            request.productId())
        .stream()
        .findFirst()
        .orElseThrow(() -> new NotFoundException("Product"));
  }

  private void confirmFamilyImage(StoredFile file, Instant now) {
    String previousKey =
        jdbc.query(
            "select image_object_key from product_families where tenant_id=? and id=?",
            result -> result.next() ? result.getString(1) : null,
            file.getTenantId(),
            file.getProductFamilyId());
    int updated =
        jdbc.update(
            """
              update product_families set image_object_key=?, updated_at=?
              where tenant_id=? and id=?
              """,
            file.getObjectKey(),
            Timestamp.from(now),
            file.getTenantId(),
            file.getProductFamilyId());
    if (updated != 1) throw new NotFoundException("Product family");
    jdbc.update(
        """
          update products set image_object_key=?, updated_at=?
          where tenant_id=? and family_id=?
          """,
        file.getObjectKey(),
        Timestamp.from(now),
        file.getTenantId(),
        file.getProductFamilyId());
    if (previousKey != null && !previousKey.equals(file.getObjectKey())) {
      repository
          .findByObjectKeyAndTenantId(previousKey, file.getTenantId())
          .ifPresent(
              previous -> {
                previous.deleted(now);
                deleteObjectsAfterCommit(previous.getObjectKey(), previous.getPreviewObjectKey());
              });
    }
  }

  private Map<String, Object> imageAuditDetails(StoredFile file) {
    Map<String, Object> details = new java.util.LinkedHashMap<>();
    if (file.getProductId() != null) details.put("productId", file.getProductId());
    if (file.getProductFamilyId() != null)
      details.put("productFamilyId", file.getProductFamilyId());
    return Map.copyOf(details);
  }

  private static void validateImage(String contentType, long size) {
    if (!IMAGE_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
      throw new BusinessException(
          "UNSUPPORTED_IMAGE_TYPE", "Only JPG, PNG and WebP images are supported");
    }
    if (size <= 0 || size > MAX_IMAGE_SIZE) {
      throw new BusinessException("IMAGE_TOO_LARGE", "Image must be no larger than 10 MB");
    }
  }

  private static void validatePreviewRequest(long size) {
    if (size <= 0 || size > MAX_PREVIEW_SIZE) {
      throw new BusinessException(
          "IMAGE_PREVIEW_TOO_LARGE", "Image preview must be no larger than 512 KB");
    }
  }

  private void validateStoredObject(
      String objectKey, String contentType, Long size, String checksum, String codePrefix) {
    if (objectKey == null || contentType == null || size == null || checksum == null) {
      throw new BusinessException(
          codePrefix + "_METADATA_MISSING", "Uploaded file metadata is incomplete");
    }
    StorageService.ObjectMetadata actual = storage.head(objectKey);
    if (actual.size() != size) {
      throw new BusinessException(
          codePrefix + "_SIZE_MISMATCH", "Uploaded file size does not match the presigned request");
    }
    if (!sameContentType(actual.contentType(), contentType)) {
      throw new BusinessException(
          codePrefix + "_CONTENT_TYPE_MISMATCH",
          "Uploaded file type does not match the presigned request");
    }
    if (!actualChecksum(objectKey).equalsIgnoreCase(checksum)) {
      throw new BusinessException(
          codePrefix + "_CHECKSUM_MISMATCH", "Uploaded file checksum could not be verified");
    }
  }

  private void validatePreviewDimensions(String previewObjectKey) {
    try (InputStream input = storage.get(previewObjectKey)) {
      WebpDimensions.Size dimensions = WebpDimensions.read(input);
      if (dimensions.width() > LegacyImagePreviewer.MAX_PREVIEW_DIMENSION
          || dimensions.height() > LegacyImagePreviewer.MAX_PREVIEW_DIMENSION) {
        throw new BusinessException(
            "IMAGE_PREVIEW_DIMENSIONS_TOO_LARGE",
            "Image preview must fit within 480 by 480 pixels");
      }
    } catch (IOException exception) {
      throw new BusinessException(
          "STORAGE_READ_FAILED", "Unable to verify image preview", HttpStatus.BAD_GATEWAY);
    }
  }

  private static boolean sameContentType(String first, String second) {
    if (first == null || second == null) return false;
    try {
      MediaType left = MediaType.parseMediaType(first);
      MediaType right = MediaType.parseMediaType(second);
      return left.getType().equalsIgnoreCase(right.getType())
          && left.getSubtype().equalsIgnoreCase(right.getSubtype());
    } catch (IllegalArgumentException exception) {
      return false;
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
      throw new BusinessException(
          "STORAGE_READ_FAILED", "Unable to verify uploaded file", HttpStatus.BAD_GATEWAY);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String safeFilename(String name) {
    String leaf = name == null ? "image" : name.replace('\\', '/');
    leaf = leaf.substring(leaf.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
    return leaf.isBlank() ? "image" : leaf.substring(0, Math.min(leaf.length(), 500));
  }

  private void deleteObjectsAfterCommit(String... objectKeys) {
    Set<String> keys = new LinkedHashSet<>();
    for (String key : objectKeys) {
      if (key != null && !key.isBlank()) keys.add(key);
    }
    if (keys.isEmpty()) return;
    Runnable deletion =
        () -> {
          for (String key : keys) {
            try {
              storage.delete(key);
            } catch (RuntimeException ignored) {
              /* orphan cleanup can retry */
            }
          }
        };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              deletion.run();
            }
          });
    } else {
      deletion.run();
    }
  }

  private static String extension(String filename, String contentType) {
    String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
    if (contentType.equalsIgnoreCase("image/jpeg")
        && (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
      return lower.endsWith(".jpeg") ? ".jpeg" : ".jpg";
    }
    if (contentType.equalsIgnoreCase("image/png") && lower.endsWith(".png")) return ".png";
    if (contentType.equalsIgnoreCase("image/webp") && lower.endsWith(".webp")) return ".webp";
    throw new BusinessException(
        "IMAGE_EXTENSION_MISMATCH", "Image extension does not match its content type");
  }

  private static String keySegment(String value) {
    StringBuilder encoded = new StringBuilder();
    for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
      int unsigned = current & 0xff;
      if ((unsigned >= 'A' && unsigned <= 'Z')
          || (unsigned >= 'a' && unsigned <= 'z')
          || (unsigned >= '0' && unsigned <= '9')
          || unsigned == '-'
          || unsigned == '_'
          || unsigned == '.'
          || unsigned == '~') {
        encoded.append((char) unsigned);
      } else {
        encoded.append('%');
        encoded.append("0123456789ABCDEF".charAt(unsigned >>> 4));
        encoded.append("0123456789ABCDEF".charAt(unsigned & 0x0f));
      }
    }
    return encoded.toString();
  }

  public record PreviewContent(String contentType, Long size, InputStream input) {}

  private record StorageIdentity(
      String tenantSlug, String resourceType, String resourceKey, UUID productFamilyId) {}
}
