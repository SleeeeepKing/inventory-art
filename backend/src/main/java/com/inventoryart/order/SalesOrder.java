package com.inventoryart.order;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="orders")
public class SalesOrder {
    @Id private UUID id;
    @Column(name="tenant_id",nullable=false) private UUID tenantId;
    @Column(name="order_number",nullable=false) private String orderNumber;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private OrderSource source;
    @Column(name="external_provider") private String externalProvider;
    @Column(name="external_transaction_id") private String externalTransactionId;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private OrderStatus status;
    @Enumerated(EnumType.STRING) @Column(name="allocation_status",nullable=false) private AllocationStatus allocationStatus;
    @Enumerated(EnumType.STRING) @Column(name="sales_channel",nullable=false) private SalesChannel salesChannel;
    @Column(name="event_name") private String eventName;
    @Column(name="customer_name") private String customerName;
    @Column(name="customer_email") private String customerEmail;
    @Column(name="customer_note") private String customerNote;
    @Column(nullable=false,length=3) private String currency;
    @Column(nullable=false) private BigDecimal subtotal;
    @Column(name="discount_amount",nullable=false) private BigDecimal discountAmount;
    @Column(name="tax_amount",nullable=false) private BigDecimal taxAmount;
    @Column(name="refund_amount",nullable=false) private BigDecimal refundAmount;
    @Column(name="total_amount",nullable=false) private BigDecimal totalAmount;
    @Column(name="unallocated_amount",nullable=false) private BigDecimal unallocatedAmount;
    @Enumerated(EnumType.STRING) @Column(name="payment_method",nullable=false) private PaymentMethod paymentMethod;
    @Enumerated(EnumType.STRING) @Column(name="payment_status",nullable=false) private PaymentStatus paymentStatus;
    @Column(name="order_date",nullable=false) private Instant orderDate;
    @Column(name="inventory_applied",nullable=false) private boolean inventoryApplied;
    @Column(name="manually_modified_after_import",nullable=false) private boolean manuallyModifiedAfterImport;
    @Column(name="import_batch_id") private UUID importBatchId;
    @Column(name="created_by") private UUID createdBy;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @Version private long version;
    protected SalesOrder(){}
    public SalesOrder(UUID id,UUID tenant,String number,OrderSource source,OrderStatus status,AllocationStatus allocation,SalesChannel channel,String event,String customerName,String customerEmail,String note,String currency,PaymentMethod method,PaymentStatus paymentStatus,Instant date,UUID createdBy){
        this.id=id;this.tenantId=tenant;this.orderNumber=number;this.source=source;this.status=status;this.allocationStatus=allocation;this.salesChannel=channel;this.eventName=event;this.customerName=customerName;this.customerEmail=customerEmail;this.customerNote=note;this.currency=currency;
        this.subtotal=BigDecimal.ZERO;this.discountAmount=BigDecimal.ZERO;this.taxAmount=BigDecimal.ZERO;this.refundAmount=BigDecimal.ZERO;this.totalAmount=BigDecimal.ZERO;this.unallocatedAmount=BigDecimal.ZERO;this.paymentMethod=method;this.paymentStatus=paymentStatus;this.orderDate=date;this.createdBy=createdBy;this.createdAt=Instant.now();this.updatedAt=createdAt;
    }
    public UUID getId(){return id;}public UUID getTenantId(){return tenantId;}public String getOrderNumber(){return orderNumber;}public OrderSource getSource(){return source;}public String getExternalProvider(){return externalProvider;}public String getExternalTransactionId(){return externalTransactionId;}public OrderStatus getStatus(){return status;}public AllocationStatus getAllocationStatus(){return allocationStatus;}public SalesChannel getSalesChannel(){return salesChannel;}public String getEventName(){return eventName;}public String getCustomerName(){return customerName;}public String getCustomerEmail(){return customerEmail;}public String getCustomerNote(){return customerNote;}public String getCurrency(){return currency;}public BigDecimal getSubtotal(){return subtotal;}public BigDecimal getDiscountAmount(){return discountAmount;}public BigDecimal getTaxAmount(){return taxAmount;}public BigDecimal getRefundAmount(){return refundAmount;}public BigDecimal getTotalAmount(){return totalAmount;}public BigDecimal getUnallocatedAmount(){return unallocatedAmount;}public PaymentMethod getPaymentMethod(){return paymentMethod;}public PaymentStatus getPaymentStatus(){return paymentStatus;}public Instant getOrderDate(){return orderDate;}public boolean isInventoryApplied(){return inventoryApplied;}public boolean isManuallyModifiedAfterImport(){return manuallyModifiedAfterImport;}public UUID getImportBatchId(){return importBatchId;}public UUID getCreatedBy(){return createdBy;}public Instant getCreatedAt(){return createdAt;}public Instant getUpdatedAt(){return updatedAt;}public long getVersion(){return version;}
    public void setAmounts(BigDecimal subtotal,BigDecimal discount,BigDecimal tax,BigDecimal total,BigDecimal unallocated){this.subtotal=subtotal;this.discountAmount=discount;this.taxAmount=tax;this.totalAmount=total;this.unallocatedAmount=unallocated;this.updatedAt=Instant.now();}
    public void updateDetails(SalesChannel channel,String event,String name,String email,String note,PaymentMethod method,PaymentStatus paymentStatus,Instant date){this.salesChannel=channel;this.eventName=event;this.customerName=name;this.customerEmail=email;this.customerNote=note;this.paymentMethod=method;this.paymentStatus=paymentStatus;this.orderDate=date;this.manuallyModifiedAfterImport=source==OrderSource.SUMUP_IMPORT;this.updatedAt=Instant.now();}
    public void confirmed(){this.status=OrderStatus.CONFIRMED;markInventoryApplied();}
    public void markInventoryApplied(){this.inventoryApplied=true;this.updatedAt=Instant.now();}
    public void cancelled(){this.status=OrderStatus.CANCELLED;this.inventoryApplied=false;this.updatedAt=Instant.now();}
    public void refunded(BigDecimal amount,boolean full){this.refundAmount=this.refundAmount.add(amount);this.status=full?OrderStatus.REFUNDED:OrderStatus.PARTIALLY_REFUNDED;this.paymentStatus=full?PaymentStatus.REFUNDED:PaymentStatus.PARTIALLY_REFUNDED;this.updatedAt=Instant.now();}
    public void setExternal(String provider,String transactionId,UUID batchId,BigDecimal unallocated){this.externalProvider=provider;this.externalTransactionId=transactionId;this.importBatchId=batchId;this.unallocatedAmount=unallocated;}
    public void setAllocationStatus(AllocationStatus status){this.allocationStatus=status;this.updatedAt=Instant.now();}
}
