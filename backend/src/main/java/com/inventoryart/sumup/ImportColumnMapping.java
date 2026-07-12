package com.inventoryart.sumup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "import_column_mappings")
public class ImportColumnMapping {
  @Id private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "import_batch_id", nullable = false)
  private UUID importBatchId;

  @Column(name = "source_column", nullable = false)
  private String sourceColumn;

  @Column(name = "target_field", nullable = false)
  private String targetField;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected ImportColumnMapping() {}

  static ImportColumnMapping of(UUID tenantId, UUID batchId, String source, String target) {
    ImportColumnMapping mapping = new ImportColumnMapping();
    mapping.id = UUID.randomUUID();
    mapping.tenantId = tenantId;
    mapping.importBatchId = batchId;
    mapping.sourceColumn = source;
    mapping.targetField = target;
    mapping.createdAt = Instant.now();
    return mapping;
  }

  public String getSourceColumn() {
    return sourceColumn;
  }

  public String getTargetField() {
    return targetField;
  }
}
