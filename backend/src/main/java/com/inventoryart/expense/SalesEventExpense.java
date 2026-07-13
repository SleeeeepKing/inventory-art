package com.inventoryart.expense;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "sales_event_expenses")
public class SalesEventExpense {
  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "category_id", nullable = false)
  private UUID categoryId;

  @Column(nullable = false)
  private BigDecimal amount;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(name = "expense_date", nullable = false)
  private LocalDate expenseDate;

  private String note;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ExpenseStatus status;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(name = "voided_by")
  private UUID voidedBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "voided_at")
  private Instant voidedAt;

  @Version private long version;

  protected SalesEventExpense() {}

  public SalesEventExpense(
      UUID tenantId,
      UUID eventId,
      UUID categoryId,
      BigDecimal amount,
      String currency,
      LocalDate expenseDate,
      String note,
      UUID userId) {
    this.id = UUID.randomUUID();
    this.tenantId = tenantId;
    this.eventId = eventId;
    this.categoryId = categoryId;
    this.amount = amount;
    this.currency = currency;
    this.expenseDate = expenseDate;
    this.note = note;
    this.status = ExpenseStatus.ACTIVE;
    this.createdBy = userId;
    this.updatedBy = userId;
    this.createdAt = Instant.now();
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

  public UUID getCategoryId() {
    return categoryId;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public LocalDate getExpenseDate() {
    return expenseDate;
  }

  public String getNote() {
    return note;
  }

  public ExpenseStatus getStatus() {
    return status;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public UUID getUpdatedBy() {
    return updatedBy;
  }

  public UUID getVoidedBy() {
    return voidedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getVoidedAt() {
    return voidedAt;
  }

  public long getVersion() {
    return version;
  }

  public void update(
      UUID categoryId, BigDecimal amount, LocalDate expenseDate, String note, UUID userId) {
    this.categoryId = categoryId;
    this.amount = amount;
    this.expenseDate = expenseDate;
    this.note = note;
    this.updatedBy = userId;
    this.updatedAt = Instant.now();
  }

  public void voidExpense(UUID userId) {
    this.status = ExpenseStatus.VOIDED;
    this.voidedBy = userId;
    this.voidedAt = Instant.now();
    this.updatedBy = userId;
    this.updatedAt = voidedAt;
  }
}
