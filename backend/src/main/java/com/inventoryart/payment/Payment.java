package com.inventoryart.payment;

import com.inventoryart.order.PaymentMethod;
import com.inventoryart.order.PaymentStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="payments")
public class Payment {
    @Id private UUID id;
    @Column(name="tenant_id",nullable=false) private UUID tenantId;
    @Column(name="order_id",nullable=false) private UUID orderId;
    private String provider;
    @Column(name="provider_transaction_id") private String providerTransactionId;
    @Column(nullable=false) private BigDecimal amount;
    @Column(nullable=false,length=3) private String currency;
    @Enumerated(EnumType.STRING) @Column(name="payment_method",nullable=false) private PaymentMethod paymentMethod;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private PaymentStatus status;
    @Column(name="paid_at") private Instant paidAt;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    protected Payment(){}
    public Payment(UUID tenantId,UUID orderId,String provider,String providerTransactionId,BigDecimal amount,String currency,PaymentMethod method,PaymentStatus status,Instant paidAt){this.id=UUID.randomUUID();this.tenantId=tenantId;this.orderId=orderId;this.provider=provider;this.providerTransactionId=providerTransactionId;this.amount=amount;this.currency=currency;this.paymentMethod=method;this.status=status;this.paidAt=paidAt;this.createdAt=Instant.now();this.updatedAt=createdAt;}
    public UUID getId(){return id;}public UUID getTenantId(){return tenantId;}public UUID getOrderId(){return orderId;}public String getProvider(){return provider;}public String getProviderTransactionId(){return providerTransactionId;}public BigDecimal getAmount(){return amount;}public String getCurrency(){return currency;}public PaymentMethod getPaymentMethod(){return paymentMethod;}public PaymentStatus getStatus(){return status;}public Instant getPaidAt(){return paidAt;}public Instant getCreatedAt(){return createdAt;}
}

