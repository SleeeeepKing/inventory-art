package com.inventoryart.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class InventorySaleDtos {
  private InventorySaleDtos() {}

  public record SaleLine(@NotNull UUID productId, @Min(1) int quantity) {}

  public record SaleRequest(
      @NotNull UUID eventId, @NotEmpty @Size(max = 100) List<@Valid SaleLine> items) {}

  public record SaleResponse(
      UUID id,
      UUID eventId,
      String eventName,
      LocalDate attributedDate,
      UUID operatorId,
      Instant createdAt,
      List<InventoryController.MovementResponse> movements) {}
}
