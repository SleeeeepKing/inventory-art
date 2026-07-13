package com.inventoryart.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_families")
public class ProductFamily {
  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(nullable = false)
  private String name;

  private String category;

  @Column(name = "artist_name")
  private String artistName;

  private String description;

  @Column(name = "image_object_key")
  private String imageObjectKey;

  @Version private long version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ProductFamily() {}

  public ProductFamily(
      UUID id, UUID tenantId, String name, String category, String artistName, String description) {
    this.id = id;
    this.tenantId = tenantId;
    this.name = name.trim();
    this.category = category;
    this.artistName = artistName;
    this.description = description;
    this.createdAt = Instant.now();
    this.updatedAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
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

  public long getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void update(String name, String category, String artistName, String description) {
    this.name = name.trim();
    this.category = category;
    this.artistName = artistName;
    this.description = description;
    this.updatedAt = Instant.now();
  }

  public void setImageObjectKey(String imageObjectKey) {
    this.imageObjectKey = imageObjectKey;
    this.updatedAt = Instant.now();
  }
}
