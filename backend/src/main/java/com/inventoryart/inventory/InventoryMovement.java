package com.inventoryart.inventory;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="inventory_movements")
public class InventoryMovement {
    @Id private UUID id;
    @Column(name="tenant_id",nullable=false) private UUID tenantId;
    @Column(name="product_id",nullable=false) private UUID productId;
    @Enumerated(EnumType.STRING) @Column(name="movement_type",nullable=false) private MovementType movementType;
    @Column(nullable=false) private int quantity;
    @Column(name="stock_before",nullable=false) private int stockBefore;
    @Column(name="stock_after",nullable=false) private int stockAfter;
    @Column(name="related_order_id") private UUID relatedOrderId;
    @Column(name="related_import_batch_id") private UUID relatedImportBatchId;
    private String reference; private String remark;
    @Column(name="operator_id") private UUID operatorId;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    protected InventoryMovement(){}
    public InventoryMovement(UUID tenantId,UUID productId,MovementType type,int quantity,int before,int after,UUID orderId,UUID batchId,String reference,String remark,UUID operatorId){
        this.id=UUID.randomUUID();this.tenantId=tenantId;this.productId=productId;this.movementType=type;this.quantity=quantity;this.stockBefore=before;
        this.stockAfter=after;this.relatedOrderId=orderId;this.relatedImportBatchId=batchId;this.reference=reference;this.remark=remark;this.operatorId=operatorId;this.createdAt=Instant.now();
    }
    public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public UUID getProductId(){return productId;} public MovementType getMovementType(){return movementType;}
    public int getQuantity(){return quantity;} public int getStockBefore(){return stockBefore;} public int getStockAfter(){return stockAfter;} public UUID getRelatedOrderId(){return relatedOrderId;}
    public UUID getRelatedImportBatchId(){return relatedImportBatchId;} public String getReference(){return reference;} public String getRemark(){return remark;} public UUID getOperatorId(){return operatorId;} public Instant getCreatedAt(){return createdAt;}
}

