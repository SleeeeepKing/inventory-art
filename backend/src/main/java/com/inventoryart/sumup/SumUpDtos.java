package com.inventoryart.sumup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SumUpDtos {
    private SumUpDtos() {}

    public record BatchResponse(
        UUID id, UUID tenantId, String sourceProvider, ImportType importType, String originalFilename, long fileSize,
        String detectedEncoding, String detectedDelimiter, int analysisVersion, ImportBatchStatus status,
        int totalRows, int validRows, int importedRows, int updatedRows, int duplicateRows, int skippedRows,
        int errorRows, int inventoryMovementCount, int orderCount, Instant createdAt, Instant startedAt,
        Instant completedAt, Instant reversedAt
    ) {
        static BatchResponse from(ImportBatch batch) {
            return new BatchResponse(batch.getId(), batch.getTenantId(), batch.getSourceProvider(), batch.getImportType(),
                batch.getOriginalFilename(), batch.getFileSize(), batch.getDetectedEncoding(),
                batch.getDetectedDelimiter(), batch.getAnalysisVersion(), batch.getStatus(), batch.getTotalRows(),
                batch.getValidRows(), batch.getImportedRows(), batch.getUpdatedRows(), batch.getDuplicateRows(),
                batch.getSkippedRows(), batch.getErrorRows(), batch.getInventoryMovementCount(), batch.getOrderCount(),
                batch.getCreatedAt(), batch.getStartedAt(), batch.getCompletedAt(), batch.getReversedAt());
        }
    }

    public record AnalyzeResponse(BatchResponse batch, List<String> sourceColumns,
                                  Map<String, String> suggestedMappings) {}

    public record ColumnMappingRequest(@Min(0) int expectedAnalysisVersion,
                                       @NotEmpty Map<@NotBlank String, @NotBlank String> mappings) {}

    public record RowResponse(UUID id, int rowNumber, String rowType, ImportRowStatus status,
                              String externalTransactionId, String fingerprint, Map<String, Object> raw,
                              Map<String, Object> normalized, List<String> validationErrors,
                              UUID linkedOrderId, UUID linkedProductId) {
        static RowResponse from(ImportRow row) {
            return new RowResponse(row.getId(), row.getRowNumber(), row.getRowType(), row.getProcessingStatus(),
                row.getExternalTransactionId(), row.getFingerprint(), row.getSanitizedRawData(),
                row.getNormalizedData(), row.getValidationErrors(), row.getLinkedOrderId(), row.getLinkedProductId());
        }
    }

    public record PreviewResponse(BatchResponse batch, List<RowResponse> rows, int estimatedNew,
                                  int estimatedDuplicates, int errors, int needsProductMapping,
                                  boolean createsOrders, boolean affectsInventory, boolean financialOnly) {}

    public record ProductMappingItem(String externalProductReference, String externalProductName,
                                     @NotNull UUID productId, boolean remember) {}

    public record ProductMappingsRequest(@Min(0) int expectedAnalysisVersion,
                                         @NotEmpty List<@Valid ProductMappingItem> mappings) {}

    public record ConfirmRequest(@Min(0) int expectedAnalysisVersion) {}

    public record ImportActionResponse(UUID batchId, ImportBatchStatus status, int importedRows,
                                       int updatedRows, int duplicateRows, int errorRows,
                                       int orderCount, int inventoryMovementCount) {}
}
