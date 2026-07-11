package com.inventoryart.sumup;

import java.util.UUID;

/**
 * Transaction boundary between the file-analysis module and order/inventory
 * modules. An implementation must make row, transaction, order and inventory
 * writes atomically and idempotently.
 */
public interface SumUpImportCommitter {
    record ConfirmCommand(UUID tenantId, UUID actorId, UUID batchId, int analysisVersion,
                          boolean applyInventory, boolean allowUnallocatedOrders) {}
    record ReverseCommand(UUID tenantId, UUID actorId, UUID batchId) {}
    record Result(int importedRows, int updatedRows, int duplicateRows, int errorRows,
                  int orderCount, int inventoryMovementCount) {}

    Result confirm(ConfirmCommand command);
    Result reverse(ReverseCommand command);
}
