package com.inventoryart.sumup;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {
    Optional<ImportBatch> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<ImportBatch> findByTenantIdAndSourceProviderAndFileChecksum(UUID tenantId, String provider, String checksum);
    Page<ImportBatch> findAllByTenantId(UUID tenantId, Pageable pageable);

    @Query("select b from ImportBatch b where (:tenantId is null or b.tenantId = :tenantId)")
    Page<ImportBatch> adminSearch(@Param("tenantId") UUID tenantId, Pageable pageable);
}
