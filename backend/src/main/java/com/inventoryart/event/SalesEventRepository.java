package com.inventoryart.event;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SalesEventRepository extends JpaRepository<SalesEvent, UUID> {
    List<SalesEvent> findAllByTenantIdAndEnabledOrderByNameAsc(UUID tenantId, boolean enabled);
    List<SalesEvent> findAllByTenantIdOrderByNameAsc(UUID tenantId);
    Optional<SalesEvent> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<SalesEvent> findByIdAndTenantIdAndEnabledTrue(UUID id, UUID tenantId);
    Optional<SalesEvent> findByTenantIdAndNameIgnoreCase(UUID tenantId, String name);
}
