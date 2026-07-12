package com.inventoryart.payment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
  Page<Payment> findAllByTenantId(UUID tenantId, Pageable pageable);

  boolean existsByTenantIdAndOrderId(UUID tenantId, UUID orderId);

  Optional<Payment> findByTenantIdAndOrderId(UUID tenantId, UUID orderId);
}
