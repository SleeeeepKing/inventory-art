package com.inventoryart.order;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_refund_items")
public class OrderRefundItem {
  @Id private UUID id;

  @Column(name = "tenant_id")
  private UUID tenantId;

  @Column(name = "refund_id")
  private UUID refundId;

  @Column(name = "order_item_id")
  private UUID orderItemId;

  private int quantity;
  private BigDecimal amount;

  @Column(name = "created_at")
  private Instant createdAt;

  protected OrderRefundItem() {}

  public OrderRefundItem(UUID tenant, UUID refund, UUID item, int quantity, BigDecimal amount) {
    id = UUID.randomUUID();
    tenantId = tenant;
    refundId = refund;
    orderItemId = item;
    this.quantity = quantity;
    this.amount = amount;
    createdAt = Instant.now();
  }
}
