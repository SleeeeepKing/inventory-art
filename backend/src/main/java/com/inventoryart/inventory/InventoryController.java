package com.inventoryart.inventory;

import com.inventoryart.common.PageResponse;
import com.inventoryart.audit.AuditService;
import com.inventoryart.order.SalesChannel;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import static com.inventoryart.common.QueryTimeBounds.from;
import static com.inventoryart.common.QueryTimeBounds.to;

@RestController
@RequestMapping("/api/v1/inventory")
@Validated
public class InventoryController {
    private final InventoryService service;private final InventorySaleService sales;private final InventoryMovementRepository movements;private final InventorySaleBatchRepository saleBatches;private final CurrentUserService current;private final AuditService audit;
    public InventoryController(InventoryService service,InventorySaleService sales,InventoryMovementRepository movements,InventorySaleBatchRepository saleBatches,CurrentUserService current,AuditService audit){this.service=service;this.sales=sales;this.movements=movements;this.saleBatches=saleBatches;this.current=current;this.audit=audit;}
    @PostMapping("/adjustments") @org.springframework.transaction.annotation.Transactional public List<MovementResponse> adjust(@Valid @RequestBody AdjustmentBatch request){
        UUID tenant=current.tenantId();List<MovementResponse> result=request.items().stream().map(i->{int delta=signed(i.type(),i.quantity());return MovementResponse.from(service.apply(tenant,i.productId(),delta,i.type(),null,null,i.reference(),blankToNull(i.remark()),current.userId()),null);}).toList();audit.record(tenant,"INVENTORY_ADJUST","INVENTORY_MOVEMENT",null,"SUCCESS",java.util.Map.of("count",result.size()));return result;
    }
    @PostMapping("/sales") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public InventorySaleDtos.SaleResponse sale(@Valid @RequestBody InventorySaleDtos.SaleRequest request){
        UUID tenant=current.tenantId();InventorySaleService.Result result=sales.record(tenant,current.userId(),request);InventorySaleBatch batch=result.batch();
        List<MovementResponse> response=result.movements().stream().map(m->MovementResponse.from(m,batch)).toList();
        audit.record(tenant,"INVENTORY_SALE_BATCH","INVENTORY_SALE_BATCH",batch.getId(),"SUCCESS",Map.of("items",response.size(),"channel",batch.getSalesChannel().name()));
        return new InventorySaleDtos.SaleResponse(batch.getId(),batch.getSalesChannel().name(),batch.getEventId(),batch.getEventName(),batch.getCurrency(),batch.getAttributedDate(),batch.getRemark(),batch.getOperatorId(),batch.getCreatedAt(),response);
    }
    @GetMapping("/movements") public PageResponse<MovementResponse> list(@RequestParam(required=false)UUID productId,@RequestParam(required=false)MovementType type,@RequestParam(required=false)SalesChannel channel,@RequestParam(required=false)UUID eventId,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)Instant from,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)Instant to,@RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="50")@Min(1)@Max(200)int size){
        UUID tenant=current.tenantId();var result=movements.search(tenant,productId,type,channel,eventId,from(from),to(to),PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"createdAt")));Map<UUID,InventorySaleBatch> batches=batches(tenant,result.getContent());return PageResponse.of(result.map(m->MovementResponse.from(m,m.getSaleBatchId()==null?null:batches.get(m.getSaleBatchId()))));
    }
    @GetMapping(value="/export",produces="text/csv") public void export(HttpServletResponse response)throws IOException{
        response.setContentType("text/csv");response.setHeader("Content-Disposition","attachment; filename=inventory-movements.csv");PrintWriter w=response.getWriter();w.println("createdAt,productId,type,quantity,stockBefore,stockAfter,channel,event,attributedDate,unitPrice,currency,attributedAmount,reference,remark");
        int page=0;org.springframework.data.domain.Page<InventoryMovement> result;
        UUID tenant=current.tenantId();do{result=movements.search(tenant,null,null,null,null,from(null),to(null),PageRequest.of(page++,1000,Sort.by(Sort.Direction.DESC,"createdAt")));Map<UUID,InventorySaleBatch>batches=batches(tenant,result.getContent());result.forEach(m->{InventorySaleBatch b=m.getSaleBatchId()==null?null:batches.get(m.getSaleBatchId());BigDecimal amount=attributedAmount(m);w.printf("%s,%s,%s,%d,%d,%d,%s,\"%s\",%s,%s,%s,%s,\"%s\",\"%s\"%n",m.getCreatedAt(),m.getProductId(),m.getMovementType(),m.getQuantity(),m.getStockBefore(),m.getStockAfter(),b==null?"":b.getSalesChannel().name(),csv(b==null?null:b.getEventName()),b==null?"":b.getAttributedDate(),m.getUnitPrice()==null?"":m.getUnitPrice(),b==null?"":b.getCurrency(),amount==null?"":amount,csv(m.getReference()),csv(m.getRemark()));});}while(result.hasNext());
    }
    private int signed(MovementType type,int quantity){if(quantity<=0)throw new com.inventoryart.exception.BusinessException("INVALID_QUANTITY","Quantity must be positive");return switch(type){case PURCHASE,ADJUSTMENT_IN,RETURN,INITIAL->quantity;case ADJUSTMENT_OUT->-quantity;default->throw new com.inventoryart.exception.BusinessException("INVALID_MOVEMENT_TYPE","Unsupported manual movement type");};}
    private Map<UUID,InventorySaleBatch> batches(UUID tenant,List<InventoryMovement> rows){List<UUID>ids=rows.stream().map(InventoryMovement::getSaleBatchId).filter(Objects::nonNull).distinct().toList();if(ids.isEmpty())return Map.of();Map<UUID,InventorySaleBatch>result=new HashMap<>();saleBatches.findAllByTenantIdAndIdIn(tenant,ids).forEach(batch->result.put(batch.getId(),batch));return result;}
    private static BigDecimal attributedAmount(InventoryMovement movement){return movement.getUnitPrice()==null?null:movement.getUnitPrice().multiply(BigDecimal.valueOf(Math.abs((long)movement.getQuantity())));}
    private static String blankToNull(String value){return value==null||value.isBlank()?null:value.trim();}
    private String csv(String value){if(value==null)return "";String safe=value;if(!safe.isEmpty()&&"=+-@".indexOf(safe.charAt(0))>=0)safe="'"+safe;return safe.replace("\"","\"\"");}
    public record AdjustmentBatch(@NotEmpty List<@Valid Adjustment> items){}
    public record Adjustment(@NotNull UUID productId,@NotNull MovementType type,@Min(1) int quantity,String reference,String remark){}
    public record MovementResponse(UUID id,UUID productId,String type,int quantity,int stockBefore,int stockAfter,UUID relatedOrderId,UUID saleBatchId,String salesChannel,UUID eventId,String eventName,LocalDate attributedDate,BigDecimal unitPrice,String currency,BigDecimal attributedAmount,String reference,String remark,UUID operatorId,Instant createdAt){static MovementResponse from(InventoryMovement m,InventorySaleBatch batch){return new MovementResponse(m.getId(),m.getProductId(),m.getMovementType().name(),m.getQuantity(),m.getStockBefore(),m.getStockAfter(),m.getRelatedOrderId(),m.getSaleBatchId(),batch==null?null:batch.getSalesChannel().name(),batch==null?null:batch.getEventId(),batch==null?null:batch.getEventName(),batch==null?null:batch.getAttributedDate(),m.getUnitPrice(),batch==null?null:batch.getCurrency(),InventoryController.attributedAmount(m),m.getReference(),m.getRemark(),m.getOperatorId(),m.getCreatedAt());}}
}
