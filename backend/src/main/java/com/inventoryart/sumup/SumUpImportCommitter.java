package com.inventoryart.sumup;

import java.util.UUID;

/**
 * Transaction boundary between the file-analysis and order modules.
 * Implementations make row, transaction, and order writes atomically and
 * idempotently. Inventory is intentionally outside this boundary.
 */
public interface SumUpImportCommitter {
    record ConfirmCommand(UUID tenantId, UUID actorId, UUID batchId, int analysisVersion) {}
    record ReverseCommand(UUID tenantId, UUID actorId, UUID batchId) {}
    record Result(int importedRows, int updatedRows, int duplicateRows, int errorRows,
                  int orderCount, int inventoryMovementCount) {}

    Result confirm(ConfirmCommand command);
    Result reverse(ReverseCommand command);
}
