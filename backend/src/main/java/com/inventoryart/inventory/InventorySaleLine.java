package com.inventoryart.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inventory_sale_lines")
public class InventorySaleLine {
  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "sale_batch_id", nullable = false)
  private UUID saleBatchId;

  @Column(name = "product_id", nullable = false)
  private UUID productId;

  @Column(nullable = false)
  private int quantity;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected InventorySaleLine() {}

  public InventorySaleLine(UUID tenantId, UUID saleBatchId, UUID productId, int quantity) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.saleBatchId = saleBatchId;
    this.productId = productId;
    this.quantity = quantity;
    this.createdAt = Instant.now();
    this.updatedAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getSaleBatchId() {
    return saleBatchId;
  }

  public UUID getProductId() {
    return productId;
  }

  public int getQuantity() {
    return quantity;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void updateQuantity(int quantity) {
    this.quantity = quantity;
    this.updatedAt = Instant.now();
  }
}
