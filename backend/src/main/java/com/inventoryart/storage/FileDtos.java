package com.inventoryart.storage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class FileDtos {
    private FileDtos() {}

    public record PresignUploadRequest(
        @NotBlank String originalFilename,
        @NotBlank String contentType,
        @Positive long size,
        @NotBlank @Pattern(regexp = "(?i)^[a-f0-9]{64}$") String checksumSha256,
        @NotNull UUID productId
    ) {}

    public record PresignUploadResponse(UUID fileId, String objectKey, String uploadUrl,
                                        Map<String, String> headers, Instant expiresAt) {}

    public record ConfirmFileResponse(UUID fileId, String status, Instant confirmedAt) {}

    public record DownloadUrlResponse(String url, Instant expiresAt) {}
}
