package com.inventoryart.order;

import com.inventoryart.event.SalesEvent;
import com.inventoryart.event.SalesEventService;
import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.tenant.Tenant;
import com.inventoryart.tenant.TenantRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
  private final OrderRepository orders;
  private final SalesEventService events;
  private final TenantRepository tenants;

  public OrderService(OrderRepository orders, SalesEventService events, TenantRepository tenants) {
    this.orders = orders;
    this.events = events;
    this.tenants = tenants;
  }

  @Transactional
  public OrderDtos.BatchCreateResponse createBatch(
      UUID tenantId, UUID userId, OrderDtos.BatchCreateRequest request) {
    Tenant tenant = requiredTenant(tenantId);
    SalesEvent event = events.requiredEnabled(tenantId, request.eventId());
    Instant orderHour = orderHour(request.orderDate(), tenant, event);
    String currency = tenant.getDefaultCurrency().toUpperCase(Locale.ROOT);
    List<OrderDtos.BatchSuccess> created = new ArrayList<>(request.orders().size());
    BigDecimal total = BigDecimal.ZERO;
    for (OrderDtos.BatchCreateLine line : request.orders()) {
      BigDecimal amount = money(line.totalAmount());
      SalesOrder order =
          orders.save(
              new SalesOrder(
                  UUID.randomUUID(),
                  tenantId,
                  number(),
                  event.getId(),
                  currency,
                  amount,
                  orderHour,
                  userId));
      created.add(new OrderDtos.BatchSuccess(order.getId(), order.getOrderNumber()));
      total = total.add(amount);
    }
    return new OrderDtos.BatchCreateResponse(
        event.getId(), event.getName(), currency, orderHour, created.size(), money(total), created);
  }

  @Transactional
  public OrderDtos.Response update(UUID tenantId, UUID id, OrderDtos.UpdateRequest request) {
    SalesOrder order =
        orders.findLocked(id, tenantId).orElseThrow(() -> new NotFoundException("Order"));
    Tenant tenant = requiredTenant(tenantId);
    SalesEvent event = events.requiredEnabled(tenantId, request.eventId());
    order.update(
        event.getId(), money(request.totalAmount()), orderHour(request.orderDate(), tenant, event));
    return response(order, event.getName());
  }

  @Transactional(readOnly = true)
  public OrderDtos.Response get(UUID tenantId, UUID id) {
    SalesOrder order =
        orders.findByIdAndTenantId(id, tenantId).orElseThrow(() -> new NotFoundException("Order"));
    return response(order, events.required(tenantId, order.getEventId()).getName());
  }

  @Transactional
  public OrderDtos.Deleted delete(UUID tenantId, UUID id) {
    SalesOrder order =
        orders.findLocked(id, tenantId).orElseThrow(() -> new NotFoundException("Order"));
    OrderDtos.Deleted deleted = new OrderDtos.Deleted(order.getId(), order.getOrderNumber());
    orders.delete(order);
    return deleted;
  }

  public OrderDtos.Response response(SalesOrder order, String eventName) {
    return new OrderDtos.Response(
        order.getId(),
        order.getOrderNumber(),
        order.getEventId(),
        eventName,
        order.getCurrency(),
        order.getTotalAmount(),
        order.getOrderDate(),
        order.getCreatedBy(),
        order.getVersion(),
        order.getCreatedAt(),
        order.getUpdatedAt());
  }

  private Tenant requiredTenant(UUID tenantId) {
    return tenants.findById(tenantId).orElseThrow(() -> new NotFoundException("Tenant"));
  }

  private Instant orderHour(Instant requested, Tenant tenant, SalesEvent event) {
    ZoneId zone = safeZone(tenant.getTimezone());
    ZonedDateTime localHour = requested.atZone(zone).truncatedTo(ChronoUnit.HOURS);
    if (localHour.toLocalDate().isBefore(event.getStartDate())
        || localHour.toLocalDate().isAfter(event.getEndDate())) {
      throw new BusinessException(
          "ORDER_OUTSIDE_EVENT", "Transaction hour must fall within the exhibition dates");
    }
    return localHour.toInstant();
  }

  private ZoneId safeZone(String timezone) {
    try {
      return ZoneId.of(timezone);
    } catch (RuntimeException ignored) {
      return ZoneOffset.UTC;
    }
  }

  private BigDecimal money(BigDecimal value) {
    BigDecimal amount = value.setScale(4, RoundingMode.HALF_UP);
    if (amount.signum() <= 0) {
      throw new BusinessException("INVALID_ORDER_AMOUNT", "Order amount must be positive");
    }
    return amount;
  }

  private String number() {
    return "ORD-"
        + DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(Instant.now())
        + "-"
        + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
  }
}
