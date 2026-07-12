package com.inventoryart.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventorySaleBatchRepository extends JpaRepository<InventorySaleBatch, UUID> {
    Optional<InventorySaleBatch> findByIdAndTenantId(UUID id, UUID tenantId);
    List<InventorySaleBatch> findAllByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);
}
