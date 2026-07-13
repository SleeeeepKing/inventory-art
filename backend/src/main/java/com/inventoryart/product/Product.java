package com.inventoryart.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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

  @Column(name = "family_id", nullable = false)
  private UUID familyId;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "family_id", insertable = false, updatable = false)
  private ProductFamily family;

  @Column(name = "variant_name")
  private String variantName;

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
      UUID id, ProductFamily family, String variantName, String sku, int lowStockThreshold) {
    this.id = id;
    this.tenantId = family.getTenantId();
    this.familyId = family.getId();
    this.family = family;
    this.variantName = variantName == null ? null : variantName.trim();
    this.sku = sku.trim().toUpperCase(Locale.ROOT);
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

  public UUID getFamilyId() {
    return familyId;
  }

  public ProductFamily getFamily() {
    return family;
  }

  public String getVariantName() {
    return variantName;
  }

  public String getName() {
    return family.getName();
  }

  public String getDisplayName() {
    return variantName == null || variantName.isBlank()
        ? family.getName()
        : family.getName() + " · " + variantName;
  }

  public String getCategory() {
    return family.getCategory();
  }

  public String getArtistName() {
    return family.getArtistName();
  }

  public String getDescription() {
    return family.getDescription();
  }

  public String getImageObjectKey() {
    return family.getImageObjectKey();
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

  public void updateVariant(String sku, String variantName, int threshold, boolean enabled) {
    this.sku = sku.trim().toUpperCase(Locale.ROOT);
    this.variantName = variantName == null ? null : variantName.trim();
    this.lowStockThreshold = threshold;
    this.enabled = enabled;
    this.updatedAt = Instant.now();
  }

  public void changeStock(int newStock) {
    if (newStock < 0) throw new IllegalArgumentException("Negative stock");
    this.currentStock = newStock;
    this.updatedAt = Instant.now();
  }
}
