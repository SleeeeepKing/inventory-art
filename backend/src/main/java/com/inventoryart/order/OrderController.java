package com.inventoryart.order;

import com.inventoryart.common.PageResponse;
import com.inventoryart.audit.AuditService;
import com.inventoryart.security.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.UUID;
import static com.inventoryart.common.QueryTimeBounds.from;
import static com.inventoryart.common.QueryTimeBounds.to;

@RestController @RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderService service;private final OrderRepository orders;private final CurrentUserService current;private final AuditService audit;
    public OrderController(OrderService service,OrderRepository orders,CurrentUserService current,AuditService audit){this.service=service;this.orders=orders;this.current=current;this.audit=audit;}
    @GetMapping public PageResponse<OrderDtos.Response> list(@RequestParam(required=false)String q,@RequestParam(required=false)OrderStatus status,@RequestParam(required=false)OrderSource source,@RequestParam(required=false)SalesChannel channel,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)Instant from,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)Instant to,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){UUID tenant=current.tenantId();return PageResponse.of(orders.search(tenant,q==null||q.isBlank()?"":q.trim(),status,source,channel,from(from),to(to),PageRequest.of(page,Math.min(size,100),Sort.by(Sort.Direction.DESC,"orderDate"))).map(o->service.response(o,java.util.List.of())));}
    @GetMapping("/{id}") public OrderDtos.Response get(@PathVariable UUID id){return service.get(current.tenantId(),id);}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public OrderDtos.Response create(@Valid @RequestBody OrderDtos.Request req){var r=service.create(current.tenantId(),current.userId(),req);audit.record(current.tenantId(),"ORDER_CREATE","ORDER",r.id(),"SUCCESS",java.util.Map.of());return r;}
    @PutMapping("/{id}") public OrderDtos.Response update(@PathVariable UUID id,@Valid @RequestBody OrderDtos.Request req){return service.update(current.tenantId(),current.userId(),id,req);}
    @PostMapping("/{id}/allocate") public OrderDtos.Response allocate(@PathVariable UUID id,@Valid @RequestBody OrderDtos.Request req){var r=service.allocate(current.tenantId(),current.userId(),id,req);audit.record(current.tenantId(),"ORDER_ALLOCATE","ORDER",id,"SUCCESS",java.util.Map.of("allocationStatus",r.allocationStatus()));return r;}
    @PostMapping("/{id}/confirm") public OrderDtos.Response confirm(@PathVariable UUID id){var r=service.confirm(current.tenantId(),current.userId(),id);audit.record(current.tenantId(),"ORDER_CONFIRM","ORDER",id,"SUCCESS",java.util.Map.of());return r;}
    @PostMapping("/{id}/cancel") public OrderDtos.Response cancel(@PathVariable UUID id){var r=service.cancel(current.tenantId(),current.userId(),id);audit.record(current.tenantId(),"ORDER_CANCEL","ORDER",id,"SUCCESS",java.util.Map.of());return r;}
    @PostMapping("/{id}/refunds") public OrderDtos.Response refund(@PathVariable UUID id,@Valid @RequestBody OrderDtos.RefundRequest req){var r=service.refund(current.tenantId(),current.userId(),id,req);audit.record(current.tenantId(),"ORDER_REFUND","ORDER",id,"SUCCESS",java.util.Map.of());return r;}
}
