package com.inventoryart.inventory;

import com.inventoryart.order.SalesChannel;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, UUID> {
  @Query(
      """
        select m from InventoryMovement m
        where m.tenantId=:tenantId
          and (:productId is null or m.productId=:productId)
          and (:type is null or m.movementType=:type)
          and (:channel is null or m.saleBatchId in (
              select b.id from InventorySaleBatch b where b.tenantId=:tenantId and b.salesChannel=:channel))
          and (:eventId is null or m.saleBatchId in (
              select b.id from InventorySaleBatch b where b.tenantId=:tenantId and b.eventId=:eventId))
          and m.createdAt>=:from and m.createdAt<:to
        """)
  Page<InventoryMovement> search(
      UUID tenantId,
      UUID productId,
      MovementType type,
      SalesChannel channel,
      UUID eventId,
      Instant from,
      Instant to,
      Pageable pageable);
}
