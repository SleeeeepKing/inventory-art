package com.inventoryart.inventory;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventorySaleLineRepository extends JpaRepository<InventorySaleLine, UUID> {
  List<InventorySaleLine> findAllByTenantIdAndSaleBatchIdOrderByProductId(
      UUID tenantId, UUID saleBatchId);

  List<InventorySaleLine> findAllByTenantIdAndSaleBatchIdIn(UUID tenantId, List<UUID> saleBatchIds);
}
