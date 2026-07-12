package com.inventoryart.inventory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventorySaleBatchRepository extends JpaRepository<InventorySaleBatch, UUID> {
  Optional<InventorySaleBatch> findByIdAndTenantId(UUID id, UUID tenantId);

  List<InventorySaleBatch> findAllByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);
}
