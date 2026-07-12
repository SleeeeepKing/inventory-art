package com.inventoryart.payment;

import com.inventoryart.common.PageResponse;
import com.inventoryart.security.CurrentUserService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
  private final PaymentRepository payments;
  private final CurrentUserService current;

  public PaymentController(PaymentRepository payments, CurrentUserService current) {
    this.payments = payments;
    this.current = current;
  }

  @GetMapping
  public PageResponse<Response> list(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return PageResponse.of(
        payments
            .findAllByTenantId(
                current.tenantId(),
                PageRequest.of(
                    page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt")))
            .map(Response::from));
  }

  public record Response(
      UUID id,
      UUID orderId,
      String provider,
      String providerTransactionId,
      BigDecimal amount,
      String currency,
      String paymentMethod,
      String status,
      Instant paidAt,
      Instant createdAt) {
    static Response from(Payment p) {
      return new Response(
          p.getId(),
          p.getOrderId(),
          p.getProvider(),
          p.getProviderTransactionId(),
          p.getAmount(),
          p.getCurrency(),
          p.getPaymentMethod().name(),
          p.getStatus().name(),
          p.getPaidAt(),
          p.getCreatedAt());
    }
  }
}
