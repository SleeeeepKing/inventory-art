package com.inventoryart.sumup;

import com.inventoryart.exception.BusinessException;
import org.springframework.http.HttpStatus;

final class SumUpExceptions {
    private SumUpExceptions() {}

    static BusinessException invalidState(ImportBatchStatus status, String operation) {
        return new BusinessException("INVALID_IMPORT_STATE",
            "Import batch in state " + status + " cannot " + operation, HttpStatus.CONFLICT);
    }
}
