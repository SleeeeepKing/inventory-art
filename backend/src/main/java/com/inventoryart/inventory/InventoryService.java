package com.inventoryart.inventory;

import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.product.Product;
import com.inventoryart.product.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class InventoryService {
    private final ProductRepository products; private final InventoryMovementRepository movements;
    public InventoryService(ProductRepository products, InventoryMovementRepository movements){this.products=products;this.movements=movements;}
    @Transactional
    public InventoryMovement apply(UUID tenantId,UUID productId,int delta,MovementType type,UUID orderId,UUID batchId,String reference,String remark,UUID operator){
        if(tenantId==null||productId==null) throw new BusinessException("INVALID_INVENTORY_TARGET","Tenant and product are required");
        if(type==null) throw new BusinessException("INVALID_MOVEMENT_TYPE","Inventory movement type is required");
        if(delta==0) throw new BusinessException("INVALID_QUANTITY","Inventory change cannot be zero");
        Product product=products.findLocked(productId,tenantId).orElseThrow(()->new NotFoundException("Product"));
        int before=product.getCurrentStock(); int after;
        try { after=Math.addExact(before,delta); } catch(ArithmeticException ex){ throw new BusinessException("INVALID_QUANTITY","Inventory quantity overflow"); }
        if(after<0) throw new BusinessException("INSUFFICIENT_STOCK","Insufficient stock");
        product.changeStock(after);
        return movements.save(new InventoryMovement(tenantId,productId,type,delta,before,after,orderId,batchId,reference,remark,operator));
    }
}
