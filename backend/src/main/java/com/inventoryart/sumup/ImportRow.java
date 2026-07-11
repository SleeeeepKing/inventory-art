package com.inventoryart.sumup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "import_rows")
public class ImportRow {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "import_batch_id", nullable = false) private UUID importBatchId;
    @Column(name = "row_number", nullable = false) private int rowNumber;
    @Column(name = "row_type", nullable = false) private String rowType;
    @Enumerated(EnumType.STRING) @Column(name = "processing_status", nullable = false) private ImportRowStatus processingStatus;
    @Column(name = "external_transaction_id") private String externalTransactionId;
    @Column(length = 64) private String fingerprint;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "normalized_data", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> normalizedData;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "sanitized_raw_data", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> sanitizedRawData;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "validation_errors", columnDefinition = "jsonb", nullable = false)
    private List<String> validationErrors;
    @Column(name = "linked_order_id") private UUID linkedOrderId;
    @Column(name = "linked_product_id") private UUID linkedProductId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected ImportRow() {}

    static ImportRow analyzed(UUID tenantId, UUID batchId, int rowNumber, ImportType type,
                              Map<String, Object> raw, Map<String, Object> normalized, List<String> errors) {
        ImportRow row = new ImportRow();
        row.id = UUID.randomUUID();
        row.tenantId = tenantId;
        row.importBatchId = batchId;
        row.rowNumber = rowNumber;
        row.rowType = type.name();
        row.sanitizedRawData = new LinkedHashMap<>(raw);
        row.normalizedData = new LinkedHashMap<>(normalized);
        row.validationErrors = List.copyOf(errors);
        row.processingStatus = errors.isEmpty() ? ImportRowStatus.VALID : ImportRowStatus.ERROR;
        Object transactionId = normalized.get("transactionId");
        row.externalTransactionId = transactionId == null ? null : transactionId.toString();
        row.fingerprint = SumUpNormalizer.fingerprint(tenantId, normalized);
        row.createdAt = Instant.now();
        return row;
    }

    void remap(ImportType type, Map<String, Object> normalized, List<String> errors) {
        rowType = type.name();
        normalizedData = new LinkedHashMap<>(normalized);
        validationErrors = List.copyOf(errors);
        processingStatus = errors.isEmpty() ? ImportRowStatus.VALID : ImportRowStatus.ERROR;
        Object transactionId = normalized.get("transactionId");
        externalTransactionId = transactionId == null ? null : transactionId.toString();
        fingerprint = SumUpNormalizer.fingerprint(tenantId, normalized);
    }

    void linkProduct(UUID productId) { this.linkedProductId = productId; }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getImportBatchId() { return importBatchId; }
    public int getRowNumber() { return rowNumber; }
    public String getRowType() { return rowType; }
    public ImportRowStatus getProcessingStatus() { return processingStatus; }
    public String getExternalTransactionId() { return externalTransactionId; }
    public String getFingerprint() { return fingerprint; }
    public Map<String, Object> getNormalizedData() { return normalizedData; }
    public Map<String, Object> getSanitizedRawData() { return sanitizedRawData; }
    public List<String> getValidationErrors() { return validationErrors; }
    public UUID getLinkedOrderId() { return linkedOrderId; }
    public UUID getLinkedProductId() { return linkedProductId; }
    public Instant getCreatedAt() { return createdAt; }
}
