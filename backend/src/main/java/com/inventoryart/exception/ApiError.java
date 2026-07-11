package com.inventoryart.exception;

import java.time.Instant;
import java.util.Map;

public record ApiError(Instant timestamp, int status, String code, String message, String path,
                       Map<String, String> fieldErrors, String traceId) {}

