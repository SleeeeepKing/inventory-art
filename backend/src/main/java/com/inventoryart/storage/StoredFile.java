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
    public enum Purpose { PRODUCT_IMAGE, IMPORT_SOURCE, ERROR_EXPORT }
    public enum Status { PENDING, CONFIRMED, DELETED }

    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "object_key", nullable = false, unique = true) private String objectKey;
    @Column(name = "original_filename") private String originalFilename;
    @Column(name = "content_type", nullable = false) private String contentType;
    private Long size;
    @Column(length = 64) private String checksum;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Purpose purpose;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status;
    @Column(name = "resource_type") private String resourceType;
    @Column(name = "resource_id") private UUID resourceId;
    @Column(name = "created_by", nullable = false) private UUID createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "confirmed_at") private Instant confirmedAt;
    @Column(name = "deleted_at") private Instant deletedAt;

    protected StoredFile() {}

    static StoredFile pending(UUID tenantId, String objectKey, String originalFilename, String contentType,
                              long size, String checksum, Purpose purpose, String resourceType,
                              UUID resourceId, UUID createdBy) {
        StoredFile file = new StoredFile();
        file.id = UUID.randomUUID();
        file.tenantId = tenantId;
        file.objectKey = objectKey;
        file.originalFilename = originalFilename;
        file.contentType = contentType;
        file.size = size;
        file.checksum = checksum;
        file.purpose = purpose;
        file.status = Status.PENDING;
        file.resourceType = resourceType;
        file.resourceId = resourceId;
        file.createdBy = createdBy;
        file.createdAt = Instant.now();
        return file;
    }

    void confirm(Instant at) { status = Status.CONFIRMED; confirmedAt = at; }
    void deleted(Instant at) { status = Status.DELETED; deletedAt = at; }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getObjectKey() { return objectKey; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public Long getSize() { return size; }
    public String getChecksum() { return checksum; }
    public Purpose getPurpose() { return purpose; }
    public Status getStatus() { return status; }
    public String getResourceType() { return resourceType; }
    public UUID getResourceId() { return resourceId; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getDeletedAt() { return deletedAt; }
}
