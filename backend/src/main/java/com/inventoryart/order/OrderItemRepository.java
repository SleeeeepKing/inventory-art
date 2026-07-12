package com.inventoryart.order;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {
  List<OrderItem> findAllByTenantIdAndOrderIdOrderByCreatedAt(UUID tenantId, UUID orderId);

  Optional<OrderItem> findByIdAndTenantIdAndOrderId(UUID id, UUID tenantId, UUID orderId);

  void deleteAllByTenantIdAndOrderId(UUID tenantId, UUID orderId);
}
