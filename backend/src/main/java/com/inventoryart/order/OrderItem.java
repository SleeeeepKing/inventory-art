package com.inventoryart.order;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_items")
public class OrderItem {
  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "order_id", nullable = false)
  private UUID orderId;

  @Column(name = "product_id")
  private UUID productId;

  @Column(name = "product_sku_snapshot")
  private String productSkuSnapshot;

  @Column(name = "product_name_snapshot", nullable = false)
  private String productNameSnapshot;

  @Column(name = "unit_price", nullable = false)
  private BigDecimal unitPrice;

  @Column(nullable = false)
  private int quantity;

  @Column(name = "discount_amount", nullable = false)
  private BigDecimal discountAmount;

  @Column(name = "tax_rate", nullable = false)
  private BigDecimal taxRate;

  @Column(name = "tax_amount", nullable = false)
  private BigDecimal taxAmount;

  @Column(name = "line_total", nullable = false)
  private BigDecimal lineTotal;

  @Column(name = "refunded_quantity", nullable = false)
  private int refundedQuantity;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected OrderItem() {}

  public OrderItem(
      UUID tenant,
      UUID order,
      UUID product,
      String sku,
      String name,
      BigDecimal unitPrice,
      int quantity,
      BigDecimal discount,
      BigDecimal taxRate,
      BigDecimal tax,
      BigDecimal total) {
    this.id = UUID.randomUUID();
    this.tenantId = tenant;
    this.orderId = order;
    this.productId = product;
    this.productSkuSnapshot = sku;
    this.productNameSnapshot = name;
    this.unitPrice = unitPrice;
    this.quantity = quantity;
    this.discountAmount = discount;
    this.taxRate = taxRate;
    this.taxAmount = tax;
    this.lineTotal = total;
    this.createdAt = Instant.now();
    this.updatedAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public UUID getProductId() {
    return productId;
  }

  public String getProductSkuSnapshot() {
    return productSkuSnapshot;
  }

  public String getProductNameSnapshot() {
    return productNameSnapshot;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public int getQuantity() {
    return quantity;
  }

  public BigDecimal getDiscountAmount() {
    return discountAmount;
  }

  public BigDecimal getTaxRate() {
    return taxRate;
  }

  public BigDecimal getTaxAmount() {
    return taxAmount;
  }

  public BigDecimal getLineTotal() {
    return lineTotal;
  }

  public int getRefundedQuantity() {
    return refundedQuantity;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public int refundableQuantity() {
    return quantity - refundedQuantity;
  }

  public void addRefunded(int qty) {
    if (qty <= 0 || qty > refundableQuantity())
      throw new IllegalArgumentException("Invalid refund quantity");
    refundedQuantity += qty;
    updatedAt = Instant.now();
  }
}
