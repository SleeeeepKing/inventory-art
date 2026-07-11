package com.inventoryart.sumup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "external_transactions")
public class ExternalTransaction {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(nullable = false) private String provider;
    @Column(name = "provider_transaction_id") private String providerTransactionId;
    @Column(name = "provider_transaction_code") private String providerTransactionCode;
    @Column(name = "provider_merchant_code") private String providerMerchantCode;
    @Column(name = "transaction_type", nullable = false) private String transactionType;
    @Column(name = "transaction_status", nullable = false) private String transactionStatus;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    @Column(nullable = false) private BigDecimal amount;
    @Column(nullable = false) private String currency;
    @Column(name = "fee_amount") private BigDecimal feeAmount;
    @Column(name = "net_amount") private BigDecimal netAmount;
    @Column(name = "refund_amount") private BigDecimal refundAmount;
    @Column(name = "payment_method") private String paymentMethod;
    @Column(name = "card_type") private String cardType;
    @Column(name = "masked_card_info") private String maskedCardInfo;
    @Column(name = "payout_reference") private String payoutReference;
    @Column(name = "payout_date") private Instant payoutDate;
    private String description;
    @Column(name = "linked_order_id") private UUID linkedOrderId;
    @Column(name = "import_batch_id", nullable = false) private UUID importBatchId;
    @Column(nullable = false, length = 64) private String fingerprint;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "raw_data", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> rawData;
    @Column(nullable = false) private boolean active;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected ExternalTransaction() {}

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getProvider() { return provider; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public String getProviderTransactionCode() { return providerTransactionCode; }
    public String getTransactionType() { return transactionType; }
    public String getTransactionStatus() { return transactionStatus; }
    public Instant getOccurredAt() { return occurredAt; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public BigDecimal getFeeAmount() { return feeAmount; }
    public BigDecimal getNetAmount() { return netAmount; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public String getPaymentMethod() { return paymentMethod; }
    public String getCardType() { return cardType; }
    public String getMaskedCardInfo() { return maskedCardInfo; }
    public String getPayoutReference() { return payoutReference; }
    public Instant getPayoutDate() { return payoutDate; }
    public String getDescription() { return description; }
    public UUID getLinkedOrderId() { return linkedOrderId; }
    public UUID getImportBatchId() { return importBatchId; }
    public boolean isActive() { return active; }
}
