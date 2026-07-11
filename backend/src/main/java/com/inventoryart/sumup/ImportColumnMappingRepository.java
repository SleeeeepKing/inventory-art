package com.inventoryart.sumup;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface ImportColumnMappingRepository extends JpaRepository<ImportColumnMapping, UUID> {
    List<ImportColumnMapping> findAllByTenantIdAndImportBatchId(UUID tenantId, UUID batchId);
    void deleteAllByTenantIdAndImportBatchId(UUID tenantId, UUID batchId);
}
