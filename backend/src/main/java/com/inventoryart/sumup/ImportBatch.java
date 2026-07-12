package com.inventoryart.sumup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "import_batches")
public class ImportBatch {
  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "source_provider", nullable = false)
  private String sourceProvider;

  @Enumerated(EnumType.STRING)
  @Column(name = "import_type", nullable = false)
  private ImportType importType;

  @Column(name = "original_filename", nullable = false)
  private String originalFilename;

  @Column(name = "stored_object_key", nullable = false)
  private String storedObjectKey;

  @Column(name = "file_checksum", nullable = false, length = 64)
  private String fileChecksum;

  @Column(name = "file_size", nullable = false)
  private long fileSize;

  @Column(name = "detected_encoding")
  private String detectedEncoding;

  @Column(name = "detected_delimiter")
  private String detectedDelimiter;

  @Column(name = "analysis_version", nullable = false)
  private int analysisVersion;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ImportBatchStatus status;

  @Column(name = "total_rows", nullable = false)
  private int totalRows;

  @Column(name = "valid_rows", nullable = false)
  private int validRows;

  @Column(name = "imported_rows", nullable = false)
  private int importedRows;

  @Column(name = "updated_rows", nullable = false)
  private int updatedRows;

  @Column(name = "duplicate_rows", nullable = false)
  private int duplicateRows;

  @Column(name = "skipped_rows", nullable = false)
  private int skippedRows;

  @Column(name = "error_rows", nullable = false)
  private int errorRows;

  @Column(name = "inventory_movement_count", nullable = false)
  private int inventoryMovementCount;

  @Column(name = "order_count", nullable = false)
  private int orderCount;

  @Column(name = "event_id")
  private UUID eventId;

  @Column(name = "event_name")
  private String eventName;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "reversed_at")
  private Instant reversedAt;

  @Column(name = "reversed_by")
  private UUID reversedBy;

  protected ImportBatch() {}

  void assignId(UUID id) {
    this.id = id;
  }

  static ImportBatch uploaded(
      UUID tenantId,
      ImportType importType,
      String filename,
      String key,
      String checksum,
      long size,
      UUID eventId,
      String eventName,
      UUID userId) {
    ImportBatch batch = new ImportBatch();
    batch.id = UUID.randomUUID();
    batch.tenantId = tenantId;
    batch.sourceProvider = "SUMUP";
    batch.importType = importType == null ? ImportType.UNKNOWN : importType;
    batch.originalFilename = filename;
    batch.storedObjectKey = key;
    batch.fileChecksum = checksum;
    batch.fileSize = size;
    batch.eventId = eventId;
    batch.eventName = eventName;
    batch.status = ImportBatchStatus.UPLOADED;
    batch.createdBy = userId;
    batch.createdAt = Instant.now();
    return batch;
  }

  void beginAnalysis() {
    if (status != ImportBatchStatus.UPLOADED
        && status != ImportBatchStatus.READY_FOR_MAPPING
        && status != ImportBatchStatus.READY_FOR_CONFIRMATION
        && status != ImportBatchStatus.FAILED) {
      throw SumUpExceptions.invalidState(status, "analyze");
    }
    status = ImportBatchStatus.ANALYZING;
    startedAt = Instant.now();
  }

  void analysisComplete(
      ImportType detectedType,
      String encoding,
      String delimiter,
      int total,
      int valid,
      int errors,
      boolean mappingRequired) {
    if (importType == ImportType.UNKNOWN && detectedType != null) importType = detectedType;
    detectedEncoding = encoding;
    detectedDelimiter = delimiter;
    totalRows = total;
    validRows = valid;
    errorRows = errors;
    analysisVersion++;
    status =
        mappingRequired
            ? ImportBatchStatus.READY_FOR_MAPPING
            : ImportBatchStatus.READY_FOR_CONFIRMATION;
  }

  void mappingApplied(int valid, int errors) {
    validRows = valid;
    errorRows = errors;
    analysisVersion++;
    status = ImportBatchStatus.READY_FOR_CONFIRMATION;
  }

  void failed() {
    status = ImportBatchStatus.FAILED;
  }

  void assignEvent(UUID eventId, String eventName) {
    if (status == ImportBatchStatus.IMPORTING
        || status == ImportBatchStatus.COMPLETED
        || status == ImportBatchStatus.COMPLETED_WITH_ERRORS
        || status == ImportBatchStatus.REVERSED) {
      throw SumUpExceptions.invalidState(status, "change its sales event");
    }
    this.eventId = eventId;
    this.eventName = eventName;
  }

  void markImporting() {
    status = ImportBatchStatus.IMPORTING;
    startedAt = Instant.now();
  }

  void markCompleted(SumUpImportCommitter.Result result) {
    importedRows = result.importedRows();
    updatedRows = result.updatedRows();
    duplicateRows = result.duplicateRows();
    errorRows = result.errorRows();
    orderCount = result.orderCount();
    inventoryMovementCount = result.inventoryMovementCount();
    completedAt = Instant.now();
    status = errorRows > 0 ? ImportBatchStatus.COMPLETED_WITH_ERRORS : ImportBatchStatus.COMPLETED;
  }

  void markReversed(UUID userId) {
    status = ImportBatchStatus.REVERSED;
    reversedAt = Instant.now();
    reversedBy = userId;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getSourceProvider() {
    return sourceProvider;
  }

  public ImportType getImportType() {
    return importType;
  }

  public String getOriginalFilename() {
    return originalFilename;
  }

  public String getStoredObjectKey() {
    return storedObjectKey;
  }

  public String getFileChecksum() {
    return fileChecksum;
  }

  public long getFileSize() {
    return fileSize;
  }

  public String getDetectedEncoding() {
    return detectedEncoding;
  }

  public String getDetectedDelimiter() {
    return detectedDelimiter;
  }

  public int getAnalysisVersion() {
    return analysisVersion;
  }

  public ImportBatchStatus getStatus() {
    return status;
  }

  public int getTotalRows() {
    return totalRows;
  }

  public int getValidRows() {
    return validRows;
  }

  public int getImportedRows() {
    return importedRows;
  }

  public int getUpdatedRows() {
    return updatedRows;
  }

  public int getDuplicateRows() {
    return duplicateRows;
  }

  public int getSkippedRows() {
    return skippedRows;
  }

  public int getErrorRows() {
    return errorRows;
  }

  public int getInventoryMovementCount() {
    return inventoryMovementCount;
  }

  public int getOrderCount() {
    return orderCount;
  }

  public UUID getEventId() {
    return eventId;
  }

  public String getEventName() {
    return eventName;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public Instant getReversedAt() {
    return reversedAt;
  }

  public UUID getReversedBy() {
    return reversedBy;
  }
}
