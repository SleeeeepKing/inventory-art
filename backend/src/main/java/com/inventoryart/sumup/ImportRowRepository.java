package com.inventoryart.sumup;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface ImportRowRepository extends JpaRepository<ImportRow, UUID> {
  Page<ImportRow> findAllByTenantIdAndImportBatchId(UUID tenantId, UUID batchId, Pageable pageable);

  List<ImportRow> findAllByTenantIdAndImportBatchIdOrderByRowNumber(UUID tenantId, UUID batchId);

  List<ImportRow> findAllByTenantIdAndImportBatchIdAndProcessingStatusInOrderByRowNumber(
      UUID tenantId, UUID batchId, Collection<ImportRowStatus> statuses);

  void deleteAllByTenantIdAndImportBatchId(UUID tenantId, UUID batchId);
}
