package com.inventoryart.payment;
import org.springframework.data.domain.*;import org.springframework.data.jpa.repository.JpaRepository;import java.util.UUID;
public interface PaymentRepository extends JpaRepository<Payment,UUID>{Page<Payment> findAllByTenantId(UUID tenantId,Pageable pageable);boolean existsByTenantIdAndOrderId(UUID tenantId,UUID orderId);}

