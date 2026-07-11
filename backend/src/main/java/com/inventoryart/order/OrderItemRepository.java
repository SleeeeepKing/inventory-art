package com.inventoryart.order;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface OrderItemRepository extends JpaRepository<OrderItem,UUID>{List<OrderItem> findAllByTenantIdAndOrderIdOrderByCreatedAt(UUID tenantId,UUID orderId);Optional<OrderItem> findByIdAndTenantIdAndOrderId(UUID id,UUID tenantId,UUID orderId);void deleteAllByTenantIdAndOrderId(UUID tenantId,UUID orderId);}

