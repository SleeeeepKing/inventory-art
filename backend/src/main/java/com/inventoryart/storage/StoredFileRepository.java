package com.inventoryart.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {
    Optional<StoredFile> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<StoredFile> findByObjectKeyAndTenantId(String objectKey, UUID tenantId);
    Optional<StoredFile> findByObjectKey(String objectKey);
}
