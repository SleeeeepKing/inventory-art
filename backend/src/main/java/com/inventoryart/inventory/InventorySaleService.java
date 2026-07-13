package com.inventoryart.inventory;

import com.inventoryart.event.SalesEvent;
import com.inventoryart.event.SalesEventRepository;
import com.inventoryart.event.SalesEventService;
import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.product.ProductRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventorySaleService {
  private final InventoryService inventory;
  private final InventorySaleBatchRepository batches;
  private final InventorySaleLineRepository saleLines;
  private final SalesEventService events;
  private final SalesEventRepository eventRepository;
  private final ProductRepository products;

  public InventorySaleService(
      InventoryService inventory,
      InventorySaleBatchRepository batches,
      InventorySaleLineRepository saleLines,
      SalesEventService events,
      SalesEventRepository eventRepository,
      ProductRepository products) {
    this.inventory = inventory;
    this.batches = batches;
    this.saleLines = saleLines;
    this.events = events;
    this.eventRepository = eventRepository;
    this.products = products;
  }

  @Transactional
  public Result record(UUID tenantId, UUID operatorId, InventorySaleDtos.SaleRequest request) {
    SalesEvent event = events.requiredEnabled(tenantId, request.eventId());
    InventorySaleBatch batch =
        batches.save(
            new InventorySaleBatch(tenantId, event.getId(), event.getEndDate(), operatorId));

    Set<UUID> productIds = new HashSet<>();
    List<InventorySaleDtos.SaleLine> lines = new ArrayList<>(request.items());
    for (InventorySaleDtos.SaleLine line : lines) {
      if (!productIds.add(line.productId())) {
        throw new BusinessException(
            "DUPLICATE_PRODUCT_IN_BATCH", "A product can only appear once in a sale batch");
      }
    }
    lines.sort(Comparator.comparing(line -> line.productId().toString()));
    List<InventoryMovement> movements =
        lines.stream()
            .map(
                line ->
                    inventory.applySale(
                        tenantId, line.productId(), line.quantity(), batch.getId(), operatorId))
            .toList();
    List<InventorySaleLine> savedLines =
        lines.stream()
            .map(
                line ->
                    saleLines.save(
                        new InventorySaleLine(
                            tenantId, batch.getId(), line.productId(), line.quantity())))
            .toList();
    return new Result(batch, event.getName(), savedLines, movements, null);
  }

  @Transactional(readOnly = true)
  public Result get(UUID tenantId, UUID batchId) {
    InventorySaleBatch batch = required(tenantId, batchId);
    SalesEvent event = requiredEvent(tenantId, batch.getEventId());
    return new Result(
        batch,
        event.getName(),
        saleLines.findAllByTenantIdAndSaleBatchIdOrderByProductId(tenantId, batchId),
        List.of(),
        null);
  }

  @Transactional
  public Result update(
      UUID tenantId, UUID operatorId, UUID batchId, InventorySaleDtos.SaleUpdateRequest request) {
    InventorySaleBatch batch = requiredActive(tenantId, batchId, request.version());
    SalesEvent event = requiredEvent(tenantId, request.eventId());
    List<InventorySaleLine> existing =
        saleLines.findAllByTenantIdAndSaleBatchIdOrderByProductId(tenantId, batchId);
    Map<UUID, Integer> before = quantities(existing);
    Map<String, Object> beforeSnapshot = snapshot(batch, before);
    Map<UUID, Integer> after = requestQuantities(request.items());
    ensureProducts(tenantId, after.keySet());

    List<UUID> affected = new ArrayList<>();
    affected.addAll(before.keySet());
    after.keySet().stream().filter(id -> !before.containsKey(id)).forEach(affected::add);
    affected.sort(Comparator.comparing(UUID::toString));
    List<InventoryMovement> movements = new ArrayList<>();
    for (UUID productId : affected) {
      int stockDelta = before.getOrDefault(productId, 0) - after.getOrDefault(productId, 0);
      if (stockDelta != 0) {
        movements.add(
            inventory.applySaleCorrection(
                tenantId,
                productId,
                stockDelta,
                batchId,
                MovementType.SALE_CORRECTION,
                operatorId));
      }
    }

    Map<UUID, InventorySaleLine> existingByProduct = new HashMap<>();
    existing.forEach(line -> existingByProduct.put(line.getProductId(), line));
    for (InventorySaleLine line : existing) {
      Integer quantity = after.get(line.getProductId());
      if (quantity == null) saleLines.delete(line);
      else if (line.getQuantity() != quantity) line.updateQuantity(quantity);
    }
    after.forEach(
        (productId, quantity) -> {
          if (!existingByProduct.containsKey(productId)) {
            saleLines.save(new InventorySaleLine(tenantId, batchId, productId, quantity));
          }
        });
    batch.updateEvent(event.getId(), event.getEndDate(), operatorId);
    List<InventorySaleLine> updated =
        saleLines.findAllByTenantIdAndSaleBatchIdOrderByProductId(tenantId, batchId);
    return new Result(batch, event.getName(), updated, movements, beforeSnapshot);
  }

  @Transactional
  public Result cancel(UUID tenantId, UUID operatorId, UUID batchId, long version) {
    InventorySaleBatch batch = requiredActive(tenantId, batchId, version);
    List<InventorySaleLine> existing =
        saleLines.findAllByTenantIdAndSaleBatchIdOrderByProductId(tenantId, batchId);
    Map<String, Object> beforeSnapshot = snapshot(batch, quantities(existing));
    List<InventoryMovement> movements = new ArrayList<>();
    existing.stream()
        .sorted(Comparator.comparing(line -> line.getProductId().toString()))
        .forEach(
            line ->
                movements.add(
                    inventory.applySaleCorrection(
                        tenantId,
                        line.getProductId(),
                        line.getQuantity(),
                        batchId,
                        MovementType.SALE_REVERSAL,
                        operatorId)));
    batch.cancel(operatorId);
    SalesEvent event = requiredEvent(tenantId, batch.getEventId());
    return new Result(batch, event.getName(), existing, movements, beforeSnapshot);
  }

  private InventorySaleBatch required(UUID tenantId, UUID batchId) {
    return batches
        .findByIdAndTenantId(batchId, tenantId)
        .orElseThrow(() -> new NotFoundException("Inventory sale"));
  }

  private InventorySaleBatch requiredActive(UUID tenantId, UUID batchId, long version) {
    InventorySaleBatch batch = required(tenantId, batchId);
    if (batch.getStatus() != InventorySaleStatus.ACTIVE) {
      throw new BusinessException(
          "INVENTORY_SALE_CANCELLED", "Inventory sale is already cancelled", HttpStatus.CONFLICT);
    }
    if (batch.getVersion() != version) {
      throw new BusinessException(
          "VERSION_CONFLICT",
          "Inventory sale changed while it was being edited",
          HttpStatus.CONFLICT);
    }
    return batch;
  }

  private SalesEvent requiredEvent(UUID tenantId, UUID eventId) {
    return eventRepository
        .findByIdAndTenantId(eventId, tenantId)
        .orElseThrow(() -> new NotFoundException("Sales event"));
  }

  private Map<UUID, Integer> requestQuantities(List<InventorySaleDtos.SaleLine> lines) {
    Map<UUID, Integer> result = new HashMap<>();
    for (InventorySaleDtos.SaleLine line : lines) {
      if (result.put(line.productId(), line.quantity()) != null) {
        throw new BusinessException(
            "DUPLICATE_PRODUCT_IN_BATCH", "A product can only appear once in a sale batch");
      }
    }
    return result;
  }

  private Map<UUID, Integer> quantities(List<InventorySaleLine> lines) {
    Map<UUID, Integer> result = new HashMap<>();
    lines.forEach(line -> result.put(line.getProductId(), line.getQuantity()));
    return result;
  }

  private void ensureProducts(UUID tenantId, Set<UUID> ids) {
    if (products.findAllByTenantIdAndIdIn(tenantId, new ArrayList<>(ids)).size() != ids.size()) {
      throw new NotFoundException("Product");
    }
  }

  private Map<String, Object> snapshot(InventorySaleBatch batch, Map<UUID, Integer> quantities) {
    List<Map<String, Object>> items =
        quantities.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
            .map(
                entry ->
                    Map.<String, Object>of(
                        "productId", entry.getKey(), "quantity", entry.getValue()))
            .toList();
    return Map.of(
        "eventId", batch.getEventId(),
        "attributedDate", batch.getAttributedDate(),
        "status", batch.getStatus().name(),
        "version", batch.getVersion(),
        "items", items);
  }

  public record Result(
      InventorySaleBatch batch,
      String eventName,
      List<InventorySaleLine> lines,
      List<InventoryMovement> movements,
      Map<String, Object> before) {}
}
