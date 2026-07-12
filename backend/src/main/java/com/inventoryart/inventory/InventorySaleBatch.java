package com.inventoryart.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "inventory_sale_batches")
public class InventorySaleBatch {
  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "attributed_date", nullable = false)
  private LocalDate attributedDate;

  @Column(name = "operator_id")
  private UUID operatorId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected InventorySaleBatch() {}

  public InventorySaleBatch(
      UUID tenantId, UUID eventId, LocalDate attributedDate, UUID operatorId) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.eventId = eventId;
    this.attributedDate = attributedDate;
    this.operatorId = operatorId;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getEventId() {
    return eventId;
  }

  public LocalDate getAttributedDate() {
    return attributedDate;
  }

  public UUID getOperatorId() {
    return operatorId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
