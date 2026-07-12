package com.inventoryart.inventory;

import static com.inventoryart.common.QueryTimeBounds.from;
import static com.inventoryart.common.QueryTimeBounds.to;

import com.inventoryart.audit.AuditService;
import com.inventoryart.common.PageResponse;
import com.inventoryart.event.SalesEvent;
import com.inventoryart.event.SalesEventRepository;
import com.inventoryart.exception.BusinessException;
import com.inventoryart.security.CurrentUserService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
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
@RequestMapping("/api/v1/inventory")
@Validated
public class InventoryController {
  private final InventoryService service;
  private final InventorySaleService sales;
  private final InventoryMovementRepository movements;
  private final InventorySaleBatchRepository saleBatches;
  private final SalesEventRepository events;
  private final CurrentUserService current;
  private final AuditService audit;

  public InventoryController(
      InventoryService service,
      InventorySaleService sales,
      InventoryMovementRepository movements,
      InventorySaleBatchRepository saleBatches,
      SalesEventRepository events,
      CurrentUserService current,
      AuditService audit) {
    this.service = service;
    this.sales = sales;
    this.movements = movements;
    this.saleBatches = saleBatches;
    this.events = events;
    this.current = current;
    this.audit = audit;
  }

  @PostMapping("/adjustments")
  @Transactional
  public List<MovementResponse> adjust(@Valid @RequestBody AdjustmentBatch request) {
    UUID tenantId = current.tenantId();
    List<MovementResponse> result =
        request.items().stream()
            .map(
                item ->
                    MovementResponse.from(
                        service.apply(
                            tenantId,
                            item.productId(),
                            signed(item.type(), item.quantity()),
                            item.type(),
                            item.reference(),
                            blankToNull(item.remark()),
                            current.userId()),
                        null,
                        null))
            .toList();
    audit.record(
        tenantId,
        "INVENTORY_ADJUST",
        "INVENTORY_MOVEMENT",
        null,
        "SUCCESS",
        Map.of("count", result.size()));
    return result;
  }

  @PutMapping("/stock/{productId}")
  public MovementResponse setStock(
      @PathVariable UUID productId, @Valid @RequestBody StockRequest request) {
    UUID tenantId = current.tenantId();
    InventoryMovement movement =
        service.setStock(
            tenantId,
            productId,
            request.quantity(),
            blankToNull(request.remark()),
            current.userId());
    audit.record(
        tenantId,
        "INVENTORY_STOCK_CORRECT",
        "INVENTORY_MOVEMENT",
        movement.getId(),
        "SUCCESS",
        Map.of(
            "productId",
            productId,
            "stockBefore",
            movement.getStockBefore(),
            "stockAfter",
            movement.getStockAfter()));
    return MovementResponse.from(movement, null, null);
  }

  @PostMapping("/sales")
  @ResponseStatus(HttpStatus.CREATED)
  public InventorySaleDtos.SaleResponse sale(
      @Valid @RequestBody InventorySaleDtos.SaleRequest request) {
    UUID tenantId = current.tenantId();
    InventorySaleService.Result result = sales.record(tenantId, current.userId(), request);
    InventorySaleBatch batch = result.batch();
    List<MovementResponse> response =
        result.movements().stream()
            .map(movement -> MovementResponse.from(movement, batch, result.eventName()))
            .toList();
    audit.record(
        tenantId,
        "INVENTORY_SALE_BATCH",
        "INVENTORY_SALE_BATCH",
        batch.getId(),
        "SUCCESS",
        Map.of("items", response.size(), "eventId", batch.getEventId()));
    return new InventorySaleDtos.SaleResponse(
        batch.getId(),
        batch.getEventId(),
        result.eventName(),
        batch.getAttributedDate(),
        batch.getOperatorId(),
        batch.getCreatedAt(),
        response);
  }

  @GetMapping("/movements")
  public PageResponse<MovementResponse> list(
      @RequestParam(required = false) UUID productId,
      @RequestParam(required = false) MovementType type,
      @RequestParam(required = false) UUID eventId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
    UUID tenantId = current.tenantId();
    var result =
        movements.search(
            tenantId,
            productId,
            type,
            eventId,
            from(from),
            to(to),
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    Map<UUID, InventorySaleBatch> batches = batches(tenantId, result.getContent());
    Map<UUID, String> eventNames = eventNames(tenantId, batches.values().stream().toList());
    return PageResponse.of(
        result.map(
            movement -> {
              UUID saleBatchId = movement.getSaleBatchId();
              InventorySaleBatch batch = saleBatchId == null ? null : batches.get(saleBatchId);
              return MovementResponse.from(
                  movement, batch, batch == null ? null : eventNames.get(batch.getEventId()));
            }));
  }

  @GetMapping(value = "/export", produces = "text/csv")
  public void export(HttpServletResponse response) throws IOException {
    response.setContentType("text/csv");
    response.setHeader("Content-Disposition", "attachment; filename=inventory-movements.csv");
    PrintWriter writer = response.getWriter();
    writer.println(
        "createdAt,productId,type,quantity,stockBefore,stockAfter,event,attributedDate,reference,remark");
    int page = 0;
    org.springframework.data.domain.Page<InventoryMovement> result;
    UUID tenantId = current.tenantId();
    do {
      result =
          movements.search(
              tenantId,
              null,
              null,
              null,
              from(null),
              to(null),
              PageRequest.of(page++, 1000, Sort.by(Sort.Direction.DESC, "createdAt")));
      Map<UUID, InventorySaleBatch> batches = batches(tenantId, result.getContent());
      Map<UUID, String> eventNames = eventNames(tenantId, batches.values().stream().toList());
      result.forEach(
          movement -> {
            UUID saleBatchId = movement.getSaleBatchId();
            InventorySaleBatch batch = saleBatchId == null ? null : batches.get(saleBatchId);
            writer.printf(
                "%s,%s,%s,%d,%d,%d,\"%s\",%s,\"%s\",\"%s\"%n",
                movement.getCreatedAt(),
                movement.getProductId(),
                movement.getMovementType(),
                movement.getQuantity(),
                movement.getStockBefore(),
                movement.getStockAfter(),
                csv(batch == null ? null : eventNames.get(batch.getEventId())),
                batch == null ? "" : batch.getAttributedDate(),
                csv(movement.getReference()),
                csv(movement.getRemark()));
          });
    } while (result.hasNext());
  }

  private int signed(MovementType type, int quantity) {
    if (quantity <= 0) {
      throw new BusinessException("INVALID_QUANTITY", "Quantity must be positive");
    }
    return switch (type) {
      case PURCHASE, ADJUSTMENT_IN, RETURN, INITIAL -> quantity;
      case ADJUSTMENT_OUT -> -quantity;
      default ->
          throw new BusinessException("INVALID_MOVEMENT_TYPE", "Unsupported manual movement type");
    };
  }

  private Map<UUID, InventorySaleBatch> batches(UUID tenantId, List<InventoryMovement> movements) {
    List<UUID> ids =
        movements.stream()
            .map(InventoryMovement::getSaleBatchId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<UUID, InventorySaleBatch> result = new HashMap<>();
    saleBatches
        .findAllByTenantIdAndIdIn(tenantId, ids)
        .forEach(batch -> result.put(batch.getId(), batch));
    return result;
  }

  private Map<UUID, String> eventNames(UUID tenantId, List<InventorySaleBatch> batches) {
    List<UUID> ids = batches.stream().map(InventorySaleBatch::getEventId).distinct().toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    return events.findAllByTenantIdAndIdIn(tenantId, ids).stream()
        .collect(Collectors.toMap(SalesEvent::getId, SalesEvent::getName));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String csv(String value) {
    if (value == null) {
      return "";
    }
    String safe = value;
    if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) {
      safe = "'" + safe;
    }
    return safe.replace("\"", "\"\"");
  }

  public record AdjustmentBatch(@NotEmpty List<@Valid Adjustment> items) {}

  public record Adjustment(
      @NotNull UUID productId,
      @NotNull MovementType type,
      @Min(1) int quantity,
      String reference,
      String remark) {}

  public record StockRequest(@NotNull @Min(0) Integer quantity, String remark) {}

  public record MovementResponse(
      UUID id,
      UUID productId,
      String type,
      int quantity,
      int stockBefore,
      int stockAfter,
      UUID saleBatchId,
      UUID eventId,
      String eventName,
      LocalDate attributedDate,
      String reference,
      String remark,
      UUID operatorId,
      Instant createdAt) {
    static MovementResponse from(
        InventoryMovement movement, InventorySaleBatch batch, String eventName) {
      return new MovementResponse(
          movement.getId(),
          movement.getProductId(),
          movement.getMovementType().name(),
          movement.getQuantity(),
          movement.getStockBefore(),
          movement.getStockAfter(),
          movement.getSaleBatchId(),
          batch == null ? null : batch.getEventId(),
          eventName,
          batch == null ? null : batch.getAttributedDate(),
          movement.getReference(),
          movement.getRemark(),
          movement.getOperatorId(),
          movement.getCreatedAt());
    }
  }
}
