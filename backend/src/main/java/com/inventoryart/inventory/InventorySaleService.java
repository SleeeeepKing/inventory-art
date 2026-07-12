package com.inventoryart.inventory;

import com.inventoryart.event.SalesEvent;
import com.inventoryart.event.SalesEventService;
import com.inventoryart.exception.BusinessException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventorySaleService {
  private final InventoryService inventory;
  private final InventorySaleBatchRepository batches;
  private final SalesEventService events;

  public InventorySaleService(
      InventoryService inventory, InventorySaleBatchRepository batches, SalesEventService events) {
    this.inventory = inventory;
    this.batches = batches;
    this.events = events;
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
    return new Result(batch, event.getName(), movements);
  }

  public record Result(
      InventorySaleBatch batch, String eventName, List<InventoryMovement> movements) {}
}
