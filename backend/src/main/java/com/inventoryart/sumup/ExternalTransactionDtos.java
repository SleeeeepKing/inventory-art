package com.inventoryart.sumup;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class ExternalTransactionDtos {
  private ExternalTransactionDtos() {}

  public record Response(
      UUID id,
      UUID tenantId,
      String provider,
      String providerTransactionId,
      String transactionCode,
      String type,
      String status,
      Instant occurredAt,
      BigDecimal amount,
      String currency,
      BigDecimal feeAmount,
      BigDecimal netAmount,
      BigDecimal refundAmount,
      String paymentMethod,
      String cardType,
      String maskedCardInfo,
      String payoutReference,
      Instant payoutDate,
      String description,
      UUID linkedOrderId,
      UUID importBatchId) {
    static Response from(ExternalTransaction transaction) {
      return new Response(
          transaction.getId(),
          transaction.getTenantId(),
          transaction.getProvider(),
          transaction.getProviderTransactionId(),
          transaction.getProviderTransactionCode(),
          transaction.getTransactionType(),
          transaction.getTransactionStatus(),
          transaction.getOccurredAt(),
          transaction.getAmount(),
          transaction.getCurrency(),
          transaction.getFeeAmount(),
          transaction.getNetAmount(),
          transaction.getRefundAmount(),
          transaction.getPaymentMethod(),
          transaction.getCardType(),
          transaction.getMaskedCardInfo(),
          transaction.getPayoutReference(),
          transaction.getPayoutDate(),
          transaction.getDescription(),
          transaction.getLinkedOrderId(),
          transaction.getImportBatchId());
    }
  }

  public record LinkOrderRequest(@NotNull UUID orderId) {}

  public record OrderMatch(
      UUID orderId,
      String orderNumber,
      BigDecimal orderAmount,
      String currency,
      Instant orderDate,
      String matchStatus) {}
}
