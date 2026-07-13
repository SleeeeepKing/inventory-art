package com.inventoryart.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stored_files")
public class StoredFile {
  public enum Status {
    PENDING,
    CONFIRMED,
    DELETED
  }

  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "object_key", nullable = false, unique = true)
  private String objectKey;

  @Column(name = "preview_object_key", unique = true)
  private String previewObjectKey;

  @Column(name = "original_filename")
  private String originalFilename;

  @Column(name = "content_type", nullable = false)
  private String contentType;

  @Column(name = "preview_content_type")
  private String previewContentType;

  private Long size;

  @Column(name = "preview_size")
  private Long previewSize;

  @Column(length = 64)
  private String checksum;

  @Column(name = "preview_checksum", length = 64)
  private String previewChecksum;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status;

  @Column(name = "product_id", nullable = false)
  private UUID productId;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "confirmed_at")
  private Instant confirmedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected StoredFile() {}

  static StoredFile pending(
      UUID tenantId,
      String objectKey,
      String previewObjectKey,
      String originalFilename,
      String contentType,
      long size,
      String checksum,
      String previewContentType,
      long previewSize,
      String previewChecksum,
      UUID productId,
      UUID createdBy) {
    StoredFile file = new StoredFile();
    file.id = UUID.randomUUID();
    file.tenantId = tenantId;
    file.objectKey = objectKey;
    file.previewObjectKey = previewObjectKey;
    file.originalFilename = originalFilename;
    file.contentType = contentType;
    file.size = size;
    file.checksum = checksum;
    file.previewContentType = previewContentType;
    file.previewSize = previewSize;
    file.previewChecksum = previewChecksum;
    file.status = Status.PENDING;
    file.productId = productId;
    file.createdBy = createdBy;
    file.createdAt = Instant.now();
    return file;
  }

  void confirm(Instant at) {
    status = Status.CONFIRMED;
    confirmedAt = at;
  }

  void deleted(Instant at) {
    status = Status.DELETED;
    deletedAt = at;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getObjectKey() {
    return objectKey;
  }

  public String getPreviewObjectKey() {
    return previewObjectKey;
  }

  public String getOriginalFilename() {
    return originalFilename;
  }

  public String getContentType() {
    return contentType;
  }

  public String getPreviewContentType() {
    return previewContentType;
  }

  public Long getSize() {
    return size;
  }

  public Long getPreviewSize() {
    return previewSize;
  }

  public String getChecksum() {
    return checksum;
  }

  public String getPreviewChecksum() {
    return previewChecksum;
  }

  public Status getStatus() {
    return status;
  }

  public UUID getProductId() {
    return productId;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getConfirmedAt() {
    return confirmedAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }
}
