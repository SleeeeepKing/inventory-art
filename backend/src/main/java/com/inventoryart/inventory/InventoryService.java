package com.inventoryart.inventory;

import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.product.Product;
import com.inventoryart.product.ProductRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {
  private final ProductRepository products;
  private final InventoryMovementRepository movements;

  public InventoryService(ProductRepository products, InventoryMovementRepository movements) {
    this.products = products;
    this.movements = movements;
  }

  @Transactional
  public InventoryMovement apply(
      UUID tenantId,
      UUID productId,
      int delta,
      MovementType type,
      String reference,
      String remark,
      UUID operator) {
    return applyInternal(tenantId, productId, delta, type, null, reference, remark, operator);
  }

  @Transactional
  public InventoryMovement applySale(
      UUID tenantId, UUID productId, int quantity, UUID saleBatchId, UUID operator) {
    if (quantity <= 0) {
      throw new BusinessException("INVALID_QUANTITY", "Sale quantity must be positive");
    }
    if (saleBatchId == null) {
      throw new BusinessException("INVALID_SALE_LINE", "Sale batch is required");
    }
    return applyInternal(
        tenantId,
        productId,
        -quantity,
        MovementType.SALE,
        saleBatchId,
        "Inventory sale",
        null,
        operator);
  }

  @Transactional
  public InventoryMovement setStock(
      UUID tenantId, UUID productId, int quantity, String remark, UUID operator) {
    if (tenantId == null || productId == null) {
      throw new BusinessException("INVALID_INVENTORY_TARGET", "Tenant and product are required");
    }
    if (quantity < 0) {
      throw new BusinessException("INVALID_QUANTITY", "Inventory quantity cannot be negative");
    }
    Product product = requiredLocked(tenantId, productId);
    int before = product.getCurrentStock();
    if (before == quantity) {
      throw new BusinessException(
          "STOCK_UNCHANGED", "The requested quantity already matches the current stock");
    }
    product.changeStock(quantity);
    return movements.save(
        new InventoryMovement(
            tenantId,
            productId,
            MovementType.STOCK_CORRECTION,
            quantity - before,
            before,
            quantity,
            null,
            "Exact stock correction",
            remark,
            operator));
  }

  private InventoryMovement applyInternal(
      UUID tenantId,
      UUID productId,
      int delta,
      MovementType type,
      UUID saleBatchId,
      String reference,
      String remark,
      UUID operator) {
    if (tenantId == null || productId == null) {
      throw new BusinessException("INVALID_INVENTORY_TARGET", "Tenant and product are required");
    }
    if (type == null) {
      throw new BusinessException("INVALID_MOVEMENT_TYPE", "Inventory movement type is required");
    }
    if (delta == 0) {
      throw new BusinessException("INVALID_QUANTITY", "Inventory change cannot be zero");
    }
    Product product = requiredLocked(tenantId, productId);
    int before = product.getCurrentStock();
    int after;
    try {
      after = Math.addExact(before, delta);
    } catch (ArithmeticException exception) {
      throw new BusinessException("INVALID_QUANTITY", "Inventory quantity overflow");
    }
    if (after < 0) {
      throw new BusinessException("INSUFFICIENT_STOCK", "Insufficient stock");
    }
    product.changeStock(after);
    return movements.save(
        new InventoryMovement(
            tenantId,
            productId,
            type,
            delta,
            before,
            after,
            saleBatchId,
            reference,
            remark,
            operator));
  }

  private Product requiredLocked(UUID tenantId, UUID productId) {
    return products
        .findLocked(productId, tenantId)
        .orElseThrow(() -> new NotFoundException("Product"));
  }
}
