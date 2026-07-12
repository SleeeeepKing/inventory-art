package com.inventoryart.inventory;

import com.inventoryart.order.SalesChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class InventorySaleDtos {
  private InventorySaleDtos() {}

  public record SaleLine(
      @NotNull UUID productId,
      @Min(1) int quantity,
      @NotNull @DecimalMin("0.0") BigDecimal unitPrice) {}

  public record SaleRequest(
      @NotNull SalesChannel salesChannel,
      UUID eventId,
      @NotNull @Pattern(regexp = "[A-Za-z]{3}") String currency,
      @Size(max = 5000) String remark,
      @NotEmpty @Size(max = 100) List<@Valid SaleLine> items) {}

  public record SaleResponse(
      UUID id,
      String salesChannel,
      UUID eventId,
      String eventName,
      String currency,
      LocalDate attributedDate,
      String remark,
      UUID operatorId,
      Instant createdAt,
      List<InventoryController.MovementResponse> movements) {}
}
