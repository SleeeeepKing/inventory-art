package com.inventoryart.inventory;

import com.inventoryart.order.SalesChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

  @Enumerated(EnumType.STRING)
  @Column(name = "sales_channel", nullable = false)
  private SalesChannel salesChannel;

  @Column(name = "event_id")
  private UUID eventId;

  @Column(name = "event_name")
  private String eventName;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(name = "attributed_date", nullable = false)
  private LocalDate attributedDate;

  private String remark;

  @Column(name = "operator_id")
  private UUID operatorId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected InventorySaleBatch() {}

  public InventorySaleBatch(
      UUID tenantId,
      SalesChannel salesChannel,
      UUID eventId,
      String eventName,
      String currency,
      LocalDate attributedDate,
      String remark,
      UUID operatorId) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.salesChannel = salesChannel;
    this.eventId = eventId;
    this.eventName = eventName;
    this.currency = currency;
    this.attributedDate = attributedDate;
    this.remark = remark;
    this.operatorId = operatorId;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public SalesChannel getSalesChannel() {
    return salesChannel;
  }

  public UUID getEventId() {
    return eventId;
  }

  public String getEventName() {
    return eventName;
  }

  public String getCurrency() {
    return currency;
  }

  public LocalDate getAttributedDate() {
    return attributedDate;
  }

  public String getRemark() {
    return remark;
  }

  public UUID getOperatorId() {
    return operatorId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
