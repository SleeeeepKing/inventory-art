package com.inventoryart.order;

import com.inventoryart.common.PageResponse;
import com.inventoryart.audit.AuditService;
import com.inventoryart.exception.BusinessException;
import com.inventoryart.security.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import static com.inventoryart.common.QueryTimeBounds.from;
import static com.inventoryart.common.QueryTimeBounds.to;

@RestController @RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderService service;private final OrderRepository orders;private final CurrentUserService current;private final AuditService audit;
    public OrderController(OrderService service,OrderRepository orders,CurrentUserService current,AuditService audit){this.service=service;this.orders=orders;this.current=current;this.audit=audit;}
    @GetMapping public PageResponse<OrderDtos.Response> list(@RequestParam(required=false)String q,@RequestParam(required=false)OrderStatus status,@RequestParam(required=false)OrderSource source,@RequestParam(required=false)SalesChannel channel,@RequestParam(required=false)UUID eventId,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)Instant from,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)Instant to,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){UUID tenant=current.tenantId();return PageResponse.of(orders.search(tenant,q==null||q.isBlank()?"":q.trim(),status,source,channel,eventId,from(from),to(to),PageRequest.of(page,Math.min(size,100),Sort.by(Sort.Direction.DESC,"orderDate"))).map(o->service.response(o,java.util.List.of())));}
    @GetMapping("/{id}") public OrderDtos.Response get(@PathVariable UUID id){return service.get(current.tenantId(),id);}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public OrderDtos.Response create(@Valid @RequestBody OrderDtos.Request req){UUID tenant=current.tenantId();var r=service.create(tenant,current.userId(),req);audit.record(tenant,"ORDER_CREATE","ORDER",r.id(),"SUCCESS",java.util.Map.of());audit.record(tenant,"ORDER_CONFIRM","ORDER",r.id(),"SUCCESS",java.util.Map.of("automatic",true));return r;}
    @PostMapping("/batch") @ResponseStatus(HttpStatus.CREATED) public OrderDtos.BatchCreateResponse createBatch(@Valid @RequestBody OrderDtos.BatchCreateRequest req){UUID tenant=current.tenantId();var r=service.createBatch(tenant,current.userId(),req);audit.record(tenant,"ORDER_BATCH_CREATE","SALES_EVENT",r.eventId(),"SUCCESS",java.util.Map.of("orders",r.orderCount(),"totalAmount",r.totalAmount().toPlainString()));return r;}
    @PutMapping("/{id}") public OrderDtos.Response update(@PathVariable UUID id,@Valid @RequestBody OrderDtos.Request req){return service.update(current.tenantId(),current.userId(),id,req);}
    @PostMapping("/{id}/allocate") public OrderDtos.Response allocate(@PathVariable UUID id,@Valid @RequestBody OrderDtos.Request req){var r=service.allocate(current.tenantId(),current.userId(),id,req);audit.record(current.tenantId(),"ORDER_ALLOCATE","ORDER",id,"SUCCESS",java.util.Map.of("allocationStatus",r.allocationStatus()));return r;}
    @PostMapping("/{id}/confirm") public OrderDtos.Response confirm(@PathVariable UUID id){var r=service.confirm(current.tenantId(),current.userId(),id);audit.record(current.tenantId(),"ORDER_CONFIRM","ORDER",id,"SUCCESS",java.util.Map.of());return r;}
    @PostMapping("/{id}/cancel") public OrderDtos.Response cancel(@PathVariable UUID id){var r=service.cancel(current.tenantId(),current.userId(),id);audit.record(current.tenantId(),"ORDER_CANCEL","ORDER",id,"SUCCESS",java.util.Map.of());return r;}
    @PostMapping("/batch-confirm") public OrderDtos.BatchResponse batchConfirm(@Valid @RequestBody OrderDtos.BatchRequest req){return batch(req,true);}
    @PostMapping("/batch-cancel") public OrderDtos.BatchResponse batchCancel(@Valid @RequestBody OrderDtos.BatchRequest req){return batch(req,false);}
    @PostMapping("/{id}/refunds") public OrderDtos.Response refund(@PathVariable UUID id,@Valid @RequestBody OrderDtos.RefundRequest req){var r=service.refund(current.tenantId(),current.userId(),id,req);audit.record(current.tenantId(),"ORDER_REFUND","ORDER",id,"SUCCESS",java.util.Map.of());return r;}
    private OrderDtos.BatchResponse batch(OrderDtos.BatchRequest req,boolean confirm){
        UUID tenant=current.tenantId(),user=current.userId();List<OrderDtos.BatchSuccess> succeeded=new ArrayList<>();List<OrderDtos.BatchFailure> failed=new ArrayList<>();
        for(UUID id:new LinkedHashSet<>(req.orderIds())){
            try{
                OrderDtos.Response response=confirm?service.confirm(tenant,user,id):service.cancel(tenant,user,id);
                succeeded.add(new OrderDtos.BatchSuccess(id,response.orderNumber(),response.status()));
                audit.record(tenant,confirm?"ORDER_CONFIRM":"ORDER_CANCEL","ORDER",id,"SUCCESS",java.util.Map.of("batch",true));
            }catch(BusinessException ex){String number=orders.findByIdAndTenantId(id,tenant).map(SalesOrder::getOrderNumber).orElse(null);failed.add(new OrderDtos.BatchFailure(id,number,ex.getCode(),ex.getMessage()));}
        }
        return new OrderDtos.BatchResponse(succeeded,failed);
    }
}
