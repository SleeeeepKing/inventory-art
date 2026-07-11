package com.inventoryart.inventory;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.UUID;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement,UUID>{
    @Query("select m from InventoryMovement m where m.tenantId=:tenantId and (:productId is null or m.productId=:productId) and (:type is null or m.movementType=:type) and (:from is null or m.createdAt>=:from) and (:to is null or m.createdAt<:to)")
    Page<InventoryMovement> search(UUID tenantId, UUID productId, MovementType type, Instant from, Instant to, Pageable pageable);
}

