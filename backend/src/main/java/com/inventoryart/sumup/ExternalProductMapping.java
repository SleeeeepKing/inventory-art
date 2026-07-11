package com.inventoryart.sumup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "external_product_mappings")
public class ExternalProductMapping {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(nullable = false) private String provider;
    @Column(name = "external_product_reference") private String externalProductReference;
    @Column(name = "external_product_name") private String externalProductName;
    @Column(name = "normalized_external_name", nullable = false) private String normalizedExternalName;
    @Column(name = "product_id", nullable = false) private UUID productId;
    @Column(name = "created_by", nullable = false) private UUID createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected ExternalProductMapping() {}

    static ExternalProductMapping create(UUID tenantId, String reference, String name, UUID productId, UUID userId) {
        ExternalProductMapping mapping = new ExternalProductMapping();
        mapping.id = UUID.randomUUID();
        mapping.tenantId = tenantId;
        mapping.provider = "SUMUP";
        mapping.externalProductReference = reference;
        mapping.externalProductName = name;
        mapping.normalizedExternalName = SumUpNormalizer.normalizeText(name == null || name.isBlank() ? reference : name);
        mapping.productId = productId;
        mapping.createdBy = userId;
        mapping.createdAt = Instant.now();
        mapping.updatedAt = mapping.createdAt;
        return mapping;
    }

    void update(String reference, String name, UUID productId) {
        externalProductReference = reference;
        externalProductName = name;
        normalizedExternalName = SumUpNormalizer.normalizeText(name == null || name.isBlank() ? reference : name);
        this.productId = productId;
        updatedAt = Instant.now();
    }
}
