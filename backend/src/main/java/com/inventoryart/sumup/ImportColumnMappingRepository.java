package com.inventoryart.sumup;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ImportColumnMappingRepository extends JpaRepository<ImportColumnMapping, UUID> {
  List<ImportColumnMapping> findAllByTenantIdAndImportBatchId(UUID tenantId, UUID batchId);

  void deleteAllByTenantIdAndImportBatchId(UUID tenantId, UUID batchId);
}
