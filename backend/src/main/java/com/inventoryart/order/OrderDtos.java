package com.inventoryart.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderDtos {
  private OrderDtos() {}

  public record UpdateRequest(
      @NotNull UUID eventId,
      @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal totalAmount,
      @NotNull Instant orderDate) {}

  public record Response(
      UUID id,
      String orderNumber,
      UUID eventId,
      String eventName,
      String currency,
      BigDecimal totalAmount,
      Instant orderDate,
      UUID createdBy,
      long version,
      Instant createdAt,
      Instant updatedAt) {}

  public record BatchCreateLine(
      @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal totalAmount) {}

  public record BatchCreateRequest(
      @NotNull UUID eventId,
      @NotNull Instant orderDate,
      @NotEmpty @Size(max = 100) List<@Valid BatchCreateLine> orders) {}

  public record BatchCreateResponse(
      UUID eventId,
      String eventName,
      String currency,
      Instant orderDate,
      int orderCount,
      BigDecimal totalAmount,
      List<BatchSuccess> orders) {}

  public record Deleted(UUID id, String orderNumber) {}

  public record BatchSuccess(UUID id, String orderNumber) {}
}
