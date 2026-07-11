package com.inventoryart.sumup;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface ExternalProductMappingRepository extends JpaRepository<ExternalProductMapping, UUID> {
    Optional<ExternalProductMapping> findByTenantIdAndProviderAndNormalizedExternalName(
        UUID tenantId, String provider, String normalizedExternalName);
}
