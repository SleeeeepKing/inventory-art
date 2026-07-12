package com.inventoryart.order;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRefundRepository extends JpaRepository<OrderRefund, UUID> {}
