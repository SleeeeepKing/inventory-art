package com.inventoryart.order;

import static com.inventoryart.common.QueryTimeBounds.from;
import static com.inventoryart.common.QueryTimeBounds.to;

import com.inventoryart.audit.AuditService;
import com.inventoryart.common.PageResponse;
import com.inventoryart.event.SalesEvent;
import com.inventoryart.event.SalesEventRepository;
import com.inventoryart.security.CurrentUserService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
  private final OrderService service;
  private final OrderRepository orders;
  private final SalesEventRepository events;
  private final CurrentUserService current;
  private final AuditService audit;

  public OrderController(
      OrderService service,
      OrderRepository orders,
      SalesEventRepository events,
      CurrentUserService current,
      AuditService audit) {
    this.service = service;
    this.orders = orders;
    this.events = events;
    this.current = current;
    this.audit = audit;
  }

  @GetMapping
  public PageResponse<OrderDtos.Response> list(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) UUID eventId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    UUID tenantId = current.tenantId();
    var result =
        orders.search(
            tenantId,
            q == null || q.isBlank() ? "" : q.trim(),
            eventId,
            from(from),
            to(to),
            PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "orderDate")));
    Map<UUID, String> eventNames =
        events
            .findAllByTenantIdAndIdIn(
                tenantId,
                result.getContent().stream().map(SalesOrder::getEventId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(SalesEvent::getId, SalesEvent::getName));
    return PageResponse.of(
        result.map(order -> service.response(order, eventNames.get(order.getEventId()))));
  }

  @GetMapping("/{id}")
  public OrderDtos.Response get(@PathVariable UUID id) {
    return service.get(current.tenantId(), id);
  }

  @PostMapping("/batch")
  @ResponseStatus(HttpStatus.CREATED)
  public OrderDtos.BatchCreateResponse createBatch(
      @Valid @RequestBody OrderDtos.BatchCreateRequest request) {
    UUID tenantId = current.tenantId();
    var response = service.createBatch(tenantId, current.userId(), request);
    audit.record(
        tenantId,
        "ORDER_BATCH_CREATE",
        "SALES_EVENT",
        response.eventId(),
        "SUCCESS",
        Map.of(
            "orders", response.orderCount(),
            "totalAmount", response.totalAmount().toPlainString(),
            "orderDate", response.orderDate().toString()));
    response
        .orders()
        .forEach(
            order ->
                audit.record(
                    tenantId,
                    "ORDER_CREATE",
                    "ORDER",
                    order.id(),
                    "SUCCESS",
                    Map.of("batch", true)));
    return response;
  }

  @PutMapping("/{id}")
  public OrderDtos.Response update(
      @PathVariable UUID id, @Valid @RequestBody OrderDtos.UpdateRequest request) {
    OrderDtos.Response response = service.update(current.tenantId(), id, request);
    audit.record(
        current.tenantId(),
        "ORDER_UPDATE",
        "ORDER",
        id,
        "SUCCESS",
        Map.of("version", response.version()));
    return response;
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    UUID tenantId = current.tenantId();
    OrderDtos.Deleted deleted = service.delete(tenantId, id);
    audit.record(
        tenantId,
        "ORDER_DELETE",
        "ORDER",
        id,
        "SUCCESS",
        Map.of("orderNumber", deleted.orderNumber()));
  }
}
