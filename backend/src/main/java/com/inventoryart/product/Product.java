package com.inventoryart.product;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "products")
public class Product {
  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false)
  private String sku;

  @Column(nullable = false)
  private String name;

  private String category;

  @Column(name = "artist_name")
  private String artistName;

  private String description;

  @Column(name = "image_object_key")
  private String imageObjectKey;

  @Column(name = "cost_price")
  private BigDecimal costPrice;

  @Column(name = "sale_price", nullable = false)
  private BigDecimal salePrice;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(name = "current_stock", nullable = false)
  private int currentStock;

  @Column(name = "low_stock_threshold", nullable = false)
  private int lowStockThreshold;

  @Column(nullable = false)
  private boolean enabled;

  @Version private long version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Product() {}

  public Product(
      UUID id,
      UUID tenantId,
      String sku,
      String name,
      String category,
      String artistName,
      String description,
      BigDecimal costPrice,
      BigDecimal salePrice,
      String currency,
      int lowStockThreshold) {
    this.id = id;
    this.tenantId = tenantId;
    this.sku = sku.trim().toUpperCase(Locale.ROOT);
    this.name = name.trim();
    this.category = category;
    this.artistName = artistName;
    this.description = description;
    this.costPrice = costPrice;
    this.salePrice = salePrice;
    this.currency = currency.toUpperCase();
    this.currentStock = 0;
    this.lowStockThreshold = lowStockThreshold;
    this.enabled = true;
    this.createdAt = Instant.now();
    this.updatedAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getSku() {
    return sku;
  }

  public String getName() {
    return name;
  }

  public String getCategory() {
    return category;
  }

  public String getArtistName() {
    return artistName;
  }

  public String getDescription() {
    return description;
  }

  public String getImageObjectKey() {
    return imageObjectKey;
  }

  public BigDecimal getCostPrice() {
    return costPrice;
  }

  public BigDecimal getSalePrice() {
    return salePrice;
  }

  public String getCurrency() {
    return currency;
  }

  public int getCurrentStock() {
    return currentStock;
  }

  public int getLowStockThreshold() {
    return lowStockThreshold;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public long getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void update(
      String sku,
      String name,
      String category,
      String artistName,
      String description,
      BigDecimal costPrice,
      BigDecimal salePrice,
      String currency,
      int threshold,
      boolean enabled) {
    this.sku = sku.trim().toUpperCase(Locale.ROOT);
    this.name = name.trim();
    this.category = category;
    this.artistName = artistName;
    this.description = description;
    this.costPrice = costPrice;
    this.salePrice = salePrice;
    this.currency = currency.toUpperCase();
    this.lowStockThreshold = threshold;
    this.enabled = enabled;
    this.updatedAt = Instant.now();
  }

  public void changeStock(int newStock) {
    if (newStock < 0) throw new IllegalArgumentException("Negative stock");
    this.currentStock = newStock;
    this.updatedAt = Instant.now();
  }

  public void setImageObjectKey(String key) {
    this.imageObjectKey = key;
    this.updatedAt = Instant.now();
  }
}
