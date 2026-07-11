package com.inventoryart.inventory;

import com.inventoryart.common.PageResponse;
import com.inventoryart.audit.AuditService;
import com.inventoryart.security.CurrentUserService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory")
@Validated
public class InventoryController {
    private final InventoryService service;private final InventoryMovementRepository movements;private final CurrentUserService current;private final AuditService audit;
    public InventoryController(InventoryService service,InventoryMovementRepository movements,CurrentUserService current,AuditService audit){this.service=service;this.movements=movements;this.current=current;this.audit=audit;}
    @PostMapping("/adjustments") @org.springframework.transaction.annotation.Transactional public List<MovementResponse> adjust(@Valid @RequestBody AdjustmentBatch request){
        UUID tenant=current.tenantId();List<MovementResponse> result=request.items().stream().map(i->{int delta=signed(i.type(),i.quantity());return MovementResponse.from(service.apply(tenant,i.productId(),delta,i.type(),null,null,i.reference(),i.remark(),current.userId()));}).toList();audit.record(tenant,"INVENTORY_ADJUST","INVENTORY_MOVEMENT",null,"SUCCESS",java.util.Map.of("count",result.size()));return result;
    }
    @GetMapping("/movements") public PageResponse<MovementResponse> list(@RequestParam(required=false)UUID productId,@RequestParam(required=false)MovementType type,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)Instant from,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)Instant to,@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="50")@Min(1)@Max(200)int size){return PageResponse.of(movements.search(current.tenantId(),productId,type,from,to,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"createdAt"))).map(MovementResponse::from));}
    @GetMapping(value="/export",produces="text/csv") public void export(HttpServletResponse response)throws IOException{
        response.setContentType("text/csv");response.setHeader("Content-Disposition","attachment; filename=inventory-movements.csv");PrintWriter w=response.getWriter();w.println("createdAt,productId,type,quantity,stockBefore,stockAfter,reference");
        int page=0;org.springframework.data.domain.Page<InventoryMovement> result;
        do{result=movements.search(current.tenantId(),null,null,null,null,PageRequest.of(page++,1000,Sort.by(Sort.Direction.DESC,"createdAt")));result.forEach(m->w.printf("%s,%s,%s,%d,%d,%d,\"%s\"%n",m.getCreatedAt(),m.getProductId(),m.getMovementType(),m.getQuantity(),m.getStockBefore(),m.getStockAfter(),csv(m.getReference())));}while(result.hasNext());
    }
    private int signed(MovementType type,int quantity){if(quantity<=0)throw new com.inventoryart.exception.BusinessException("INVALID_QUANTITY","Quantity must be positive");return switch(type){case PURCHASE,ADJUSTMENT_IN,RETURN,INITIAL->quantity;case ADJUSTMENT_OUT->-quantity;default->throw new com.inventoryart.exception.BusinessException("INVALID_MOVEMENT_TYPE","Unsupported manual movement type");};}
    private String csv(String value){if(value==null)return "";String safe=value;if(!safe.isEmpty()&&"=+-@".indexOf(safe.charAt(0))>=0)safe="'"+safe;return safe.replace("\"","\"\"");}
    public record AdjustmentBatch(@NotEmpty List<@Valid Adjustment> items){}
    public record Adjustment(@NotNull UUID productId,@NotNull MovementType type,@Min(1) int quantity,String reference,String remark){}
    public record MovementResponse(UUID id,UUID productId,String type,int quantity,int stockBefore,int stockAfter,UUID relatedOrderId,String reference,String remark,UUID operatorId,Instant createdAt){static MovementResponse from(InventoryMovement m){return new MovementResponse(m.getId(),m.getProductId(),m.getMovementType().name(),m.getQuantity(),m.getStockBefore(),m.getStockAfter(),m.getRelatedOrderId(),m.getReference(),m.getRemark(),m.getOperatorId(),m.getCreatedAt());}}
}
