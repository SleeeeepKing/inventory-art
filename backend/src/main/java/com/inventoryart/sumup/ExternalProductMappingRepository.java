package com.inventoryart.sumup;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ExternalProductMappingRepository extends JpaRepository<ExternalProductMapping, UUID> {
  Optional<ExternalProductMapping> findByTenantIdAndProviderAndNormalizedExternalName(
      UUID tenantId, String provider, String normalizedExternalName);
}
