package com.inventoryart.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private InventorySaleStatus status;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "cancelled_by")
  private UUID cancelledBy;

  @Column(name = "cancelled_at")
  private Instant cancelledAt;

  @Version private long version;

  protected InventorySaleBatch() {}

  public InventorySaleBatch(
      UUID tenantId, UUID eventId, LocalDate attributedDate, UUID operatorId) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.eventId = eventId;
    this.attributedDate = attributedDate;
    this.operatorId = operatorId;
    this.createdAt = Instant.now();
    this.status = InventorySaleStatus.ACTIVE;
    this.updatedBy = operatorId;
    this.updatedAt = createdAt;
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

  public InventorySaleStatus getStatus() {
    return status;
  }

  public UUID getUpdatedBy() {
    return updatedBy;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public UUID getCancelledBy() {
    return cancelledBy;
  }

  public Instant getCancelledAt() {
    return cancelledAt;
  }

  public long getVersion() {
    return version;
  }

  public void updateEvent(UUID eventId, LocalDate attributedDate, UUID userId) {
    this.eventId = eventId;
    this.attributedDate = attributedDate;
    this.updatedBy = userId;
    this.updatedAt = Instant.now();
  }

  public void touch(UUID userId) {
    this.updatedBy = userId;
    this.updatedAt = Instant.now();
  }

  public void cancel(UUID userId) {
    this.status = InventorySaleStatus.CANCELLED;
    this.updatedBy = userId;
    this.updatedAt = Instant.now();
    this.cancelledBy = userId;
    this.cancelledAt = updatedAt;
  }
}
