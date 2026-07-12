package com.inventoryart.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class SalesOrder {
  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "order_number", nullable = false)
  private String orderNumber;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(name = "total_amount", nullable = false)
  private BigDecimal totalAmount;

  @Column(name = "order_date", nullable = false)
  private Instant orderDate;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version private long version;

  protected SalesOrder() {}

  public SalesOrder(
      UUID id,
      UUID tenantId,
      String orderNumber,
      UUID eventId,
      String currency,
      BigDecimal totalAmount,
      Instant orderDate,
      UUID createdBy) {
    this.id = id;
    this.tenantId = tenantId;
    this.orderNumber = orderNumber;
    this.eventId = eventId;
    this.currency = currency;
    this.totalAmount = totalAmount;
    this.orderDate = orderDate;
    this.createdBy = createdBy;
    this.createdAt = Instant.now();
    this.updatedAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getOrderNumber() {
    return orderNumber;
  }

  public UUID getEventId() {
    return eventId;
  }

  public String getCurrency() {
    return currency;
  }

  public BigDecimal getTotalAmount() {
    return totalAmount;
  }

  public Instant getOrderDate() {
    return orderDate;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }

  public void update(UUID eventId, BigDecimal totalAmount, Instant orderDate) {
    this.eventId = eventId;
    this.totalAmount = totalAmount;
    this.orderDate = orderDate;
    this.updatedAt = Instant.now();
  }
}
