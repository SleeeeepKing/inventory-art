package com.inventoryart.inventory;

import com.inventoryart.event.SalesEvent;
import com.inventoryart.event.SalesEventService;
import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.order.SalesChannel;
import com.inventoryart.tenant.Tenant;
import com.inventoryart.tenant.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class InventorySaleService {
    private final InventoryService inventory;
    private final InventorySaleBatchRepository batches;
    private final SalesEventService events;
    private final TenantRepository tenants;

    public InventorySaleService(InventoryService inventory, InventorySaleBatchRepository batches,
                                SalesEventService events, TenantRepository tenants) {
        this.inventory = inventory;
        this.batches = batches;
        this.events = events;
        this.tenants = tenants;
    }

    @Transactional
    public Result record(UUID tenantId, UUID operatorId, InventorySaleDtos.SaleRequest request) {
        if (request.salesChannel() == SalesChannel.SUMUP) {
            throw new BusinessException("INVALID_SALES_CHANNEL", "SumUp is a payment method, not a sales channel");
        }
        SalesEvent event = null;
        if (request.salesChannel() == SalesChannel.EXHIBITION) {
            if (request.eventId() == null) {
                throw new BusinessException("SALES_EVENT_REQUIRED", "An exhibition sale must select an event");
            }
            event = events.requiredEnabled(tenantId, request.eventId());
        } else if (request.eventId() != null) {
            throw new BusinessException("INVALID_SALES_EVENT", "Only exhibition sales can select an event");
        }

        Tenant tenant = tenants.findById(tenantId).orElseThrow(() -> new NotFoundException("Tenant"));
        LocalDate attributedDate = event == null
            ? LocalDate.now(safeZone(tenant.getTimezone()))
            : event.getEndDate();
        String remark = request.remark() == null || request.remark().isBlank() ? null : request.remark().trim();
        InventorySaleBatch batch = batches.save(new InventorySaleBatch(
            tenantId, request.salesChannel(), event == null ? null : event.getId(),
            event == null ? null : event.getName(), request.currency().toUpperCase(Locale.ROOT),
            attributedDate, remark, operatorId));

        Set<UUID> productIds = new HashSet<>();
        List<InventorySaleDtos.SaleLine> lines = new ArrayList<>(request.items());
        for (InventorySaleDtos.SaleLine line : lines) {
            if (!productIds.add(line.productId())) {
                throw new BusinessException("DUPLICATE_PRODUCT_IN_BATCH", "A product can only appear once in a sale batch");
            }
        }
        lines.sort(Comparator.comparing(line -> line.productId().toString()));
        List<InventoryMovement> movements = lines.stream()
            .map(line -> inventory.applySale(tenantId, line.productId(), line.quantity(), batch.getId(),
                line.unitPrice(), remark, operatorId))
            .toList();
        return new Result(batch, movements);
    }

    private ZoneId safeZone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (RuntimeException ignored) {
            return ZoneId.of("UTC");
        }
    }

    public record Result(InventorySaleBatch batch, List<InventoryMovement> movements) {}
}
