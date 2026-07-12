package com.inventoryart.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderDtos {
    private OrderDtos(){}
    public record ItemRequest(@NotNull UUID productId,@Min(1) int quantity,@DecimalMin("0.0") BigDecimal unitPrice,@DecimalMin("0.0") BigDecimal discountAmount,@DecimalMin("0.0") @DecimalMax("100.0") BigDecimal taxRate){}
    public record Request(@Size(max=500) List<@Valid ItemRequest> items,@NotNull @DecimalMin(value="0.0",inclusive=false) BigDecimal totalAmount,@NotNull SalesChannel salesChannel,UUID eventId,@Size(max=240)String eventName,@Size(max=240)String customerName,@Email @Size(max=254)String customerEmail,@Size(max=5000)String customerNote,@NotBlank @Pattern(regexp="[A-Za-z]{3}")String currency,@NotNull PaymentMethod paymentMethod,@NotNull PaymentStatus paymentStatus,@NotNull Instant orderDate){}
    public record ItemResponse(UUID id,UUID productId,String sku,String name,BigDecimal unitPrice,int quantity,BigDecimal discountAmount,BigDecimal taxRate,BigDecimal taxAmount,BigDecimal lineTotal,int refundedQuantity){}
    public record Response(UUID id,String orderNumber,String source,String status,String allocationStatus,String salesChannel,UUID eventId,String eventName,String customerName,String customerEmail,String customerNote,String currency,BigDecimal subtotal,BigDecimal discountAmount,BigDecimal taxAmount,BigDecimal refundAmount,BigDecimal totalAmount,BigDecimal unallocatedAmount,String paymentMethod,String paymentStatus,Instant orderDate,UUID createdBy,long version,List<ItemResponse> items,Instant createdAt,Instant updatedAt){}
    public record BatchCreateLine(@NotNull @DecimalMin(value="0.0",inclusive=false) BigDecimal totalAmount){}
    public record BatchCreateRequest(@NotNull UUID eventId,@NotBlank @Pattern(regexp="[A-Za-z]{3}")String currency,@NotNull PaymentMethod paymentMethod,@NotNull PaymentStatus paymentStatus,@NotNull Instant orderDate,@NotEmpty @Size(max=100) List<@Valid BatchCreateLine> orders){}
    public record BatchCreateResponse(UUID eventId,String eventName,String currency,int orderCount,BigDecimal totalAmount,List<BatchSuccess> orders){}
    public record BatchRequest(@NotEmpty @Size(max=100) List<@NotNull UUID> orderIds){}
    public record BatchSuccess(UUID id,String orderNumber,String status){}
    public record BatchFailure(UUID id,String orderNumber,String code,String message){}
    public record BatchResponse(List<BatchSuccess> succeeded,List<BatchFailure> failed){}
    public record RefundRequest(@Size(max=500) List<@Valid RefundLine> items,@DecimalMin(value="0.0",inclusive=false) BigDecimal amount,@Size(max=2000)String reason){}
    public record RefundLine(@NotNull UUID orderItemId,@Min(1) int quantity){}
}

