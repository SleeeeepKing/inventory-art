package com.inventoryart.sumup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.inventory.InventoryService;
import com.inventoryart.inventory.MovementType;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * PostgreSQL-backed implementation of the import commit boundary.
 *
 * <p>The parser deliberately has no dependency on orders or inventory. This
 * service is therefore the single transaction in which analyzed rows become
 * financial records, orders and stock movements. Tenant and batch rows are
 * locked before any write so a confirmation cannot be raced or replayed.</p>
 */
@Service
public class DefaultSumUpImportCommitter implements SumUpImportCommitter {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Set<String> SUCCESS_STATUSES = Set.of(
        "", "SUCCESS", "SUCCESSFUL", "SUCCEEDED", "PAID", "COMPLETED", "COMPLETE", "APPROVED"
    );
    private static final Set<String> NON_SALE_TYPES = Set.of(
        "REFUND", "REFUNDED", "CHARGEBACK", "CHARGE_BACK", "VOID", "REVERSAL"
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final InventoryService inventory;
    private final EntityManager entityManager;

    public DefaultSumUpImportCommitter(JdbcTemplate jdbc, ObjectMapper json,
                                       InventoryService inventory, EntityManager entityManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.inventory = inventory;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public Result confirm(ConfirmCommand command) {
        requireConfirmCommand(command);
        Batch batch = lockedBatch(command.batchId(), command.tenantId());
        if (batch.analysisVersion() != command.analysisVersion()) {
            throw new BusinessException("STALE_IMPORT_ANALYSIS",
                "Import analysis has changed; reload the preview", HttpStatus.CONFLICT);
        }
        if (batch.status() != ImportBatchStatus.READY_FOR_CONFIRMATION
            && batch.status() != ImportBatchStatus.IMPORTING) {
            throw SumUpExceptions.invalidState(batch.status(), "be confirmed");
        }
        verifyActor(command.actorId(), command.tenantId());
        lockTenant(command.tenantId());
        jdbc.update("""
            update import_batches set status = 'IMPORTING', started_at = coalesce(started_at, now())
            where id = ? and tenant_id = ?
            """, command.batchId(), command.tenantId());

        TenantDefaults defaults = tenantDefaults(command.tenantId());
        List<Row> validRows = analyzedRows(command.tenantId(), command.batchId());
        switch (batch.importType()) {
            case TRANSACTION_HISTORY -> confirmTransactions(command, batch, defaults, validRows);
            case ORDER_HISTORY -> confirmOrders(command, batch, defaults, validRows);
            case PRODUCT_SALES -> confirmProductSummary(command, batch, defaults, validRows);
            case ACCOUNTING_REPORT -> confirmAccountingSummary(command, defaults, validRows);
            case UNKNOWN -> throw new BusinessException("IMPORT_TYPE_UNKNOWN",
                "The import type must be selected before confirmation");
        }

        // InventoryService writes through JPA while the rest of this boundary uses
        // JDBC. Flush before deriving authoritative counters from PostgreSQL.
        entityManager.flush();
        Result result = resultFor(command.tenantId(), command.batchId());
        int skippedRows = rowCount(command.tenantId(), command.batchId(), ImportRowStatus.SKIPPED);
        ImportBatchStatus completedStatus = result.errorRows() > 0
            ? ImportBatchStatus.COMPLETED_WITH_ERRORS : ImportBatchStatus.COMPLETED;
        jdbc.update("""
            update import_batches
               set imported_rows = ?, updated_rows = ?, duplicate_rows = ?, skipped_rows = ?, error_rows = ?,
                   order_count = ?, inventory_movement_count = ?, status = ?, completed_at = now()
             where id = ? and tenant_id = ? and analysis_version = ?
            """, result.importedRows(), result.updatedRows(), result.duplicateRows(), skippedRows,
            result.errorRows(), result.orderCount(), result.inventoryMovementCount(), completedStatus.name(),
            command.batchId(), command.tenantId(), command.analysisVersion());
        return result;
    }

    @Override
    @Transactional
    public Result reverse(ReverseCommand command) {
        requireReverseCommand(command);
        Batch batch = lockedBatch(command.batchId(), command.tenantId());
        if (batch.status() == ImportBatchStatus.REVERSED) {
            return new Result(batch.importedRows(), batch.updatedRows(), batch.duplicateRows(), batch.errorRows(),
                batch.orderCount(), 0);
        }
        if (batch.status() != ImportBatchStatus.COMPLETED
            && batch.status() != ImportBatchStatus.COMPLETED_WITH_ERRORS) {
            throw SumUpExceptions.invalidState(batch.status(), "be reversed");
        }
        verifyActor(command.actorId(), command.tenantId());
        lockTenant(command.tenantId());
        refuseUnsafeReversal(command);

        List<StockDelta> reversals = jdbc.query("""
            select product_id, -sum(quantity)::bigint as reversal_quantity
              from inventory_movements
             where tenant_id = ? and related_import_batch_id = ? and movement_type = 'SUMUP_IMPORT'
             group by product_id
             order by product_id
            """, (rs, rowNum) -> new StockDelta((UUID) rs.getObject("product_id"),
            rs.getLong("reversal_quantity")), command.tenantId(), command.batchId());
        for (StockDelta reversal : reversals) {
            int quantity;
            try {
                quantity = Math.toIntExact(reversal.quantity());
            } catch (ArithmeticException exception) {
                throw new BusinessException("IMPORT_REVERSAL_OVERFLOW",
                    "The stock reversal is outside the supported quantity range", HttpStatus.CONFLICT);
            }
            if (quantity == 0) continue;
            inventory.apply(command.tenantId(), reversal.productId(), quantity, MovementType.SUMUP_REVERSAL,
                null, command.batchId(), "SUMUP-REVERSAL-" + command.batchId(),
                "Reversal of SumUp import batch", command.actorId());
        }
        entityManager.flush();

        int cancelledOrders = jdbc.update("""
            update orders
               set status = 'CANCELLED', inventory_applied = false, updated_at = now(), version = version + 1
             where tenant_id = ? and import_batch_id = ? and source = 'SUMUP_IMPORT'
            """, command.tenantId(), command.batchId());
        jdbc.update("""
            update external_transactions set active = false, updated_at = now()
             where tenant_id = ? and import_batch_id = ?
            """, command.tenantId(), command.batchId());
        jdbc.update("""
            update imported_sales_summaries set inventory_applied = false
             where tenant_id = ? and import_batch_id = ?
            """, command.tenantId(), command.batchId());
        jdbc.update("""
            update import_batches set status = 'REVERSED', reversed_at = now(), reversed_by = ?
             where tenant_id = ? and id = ?
            """, command.actorId(), command.tenantId(), command.batchId());
        return new Result(batch.importedRows(), batch.updatedRows(), batch.duplicateRows(), batch.errorRows(),
            cancelledOrders, reversals.size());
    }

    private void confirmTransactions(ConfirmCommand command, Batch batch, TenantDefaults defaults, List<Row> rows) {
        for (Row row : rows) {
            try {
                ExternalUpsert external = upsertExternal(command, batch, defaults, row);
                UUID orderId = external.linkedOrderId();
                if (orderId != null && external.active() && isRefundUpdate(row.normalized())) {
                    applyImportedRefund(command, external.id(), orderId, List.of(row));
                } else if (orderId == null && isSuccessfulSale(row.normalized())) {
                    BigDecimal amount = amount(row.normalized(), "amount", "grossRevenue", "revenue", "netRevenue");
                    if (amount.compareTo(BigDecimal.ZERO) > 0) {
                        orderId = createTransactionOrder(command, defaults, row, amount);
                        linkExternalOrder(external.id(), command.tenantId(), orderId);
                    }
                }
                mark(row, statusFor(external.outcome()), orderId, null);
            } catch (RowProblem problem) {
                mark(row, ImportRowStatus.ERROR, null, problem.code());
            }
        }
    }

    private void confirmOrders(ConfirmCommand command, Batch batch, TenantDefaults defaults, List<Row> rows) {
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        for (Row row : rows) groups.computeIfAbsent(orderGroup(row), ignored -> new ArrayList<>()).add(row);

        for (Map.Entry<String, List<Row>> entry : groups.entrySet()) {
            List<Row> group = entry.getValue();
            Row representative = group.getFirst();
            try {
                String orderCurrency = singleCurrency(group, defaults.currency());
                ExternalUpsert external = upsertExternal(command, batch, defaults, representative);
                if (external.linkedOrderId() != null && external.active()
                    && isRefundUpdate(representative.normalized())) {
                    applyImportedRefund(command, external.id(), external.linkedOrderId(), group);
                    markAll(group, statusFor(external.outcome()), external.linkedOrderId(), null);
                    continue;
                }
                if (!isSuccessfulSale(representative.normalized())) {
                    markAll(group, statusFor(external.outcome()), external.linkedOrderId(), null);
                    continue;
                }
                if (external.linkedOrderId() != null) {
                    markAll(group, statusFor(external.outcome()), external.linkedOrderId(), null);
                    continue;
                }

                List<Line> lines = orderLines(command.tenantId(), group, orderCurrency);
                boolean fullyMapped = lines.stream().allMatch(line -> line.product() != null);
                Map<UUID, Integer> quantities = quantities(lines);
                boolean inventoryCanBeApplied = command.applyInventory() && fullyMapped
                    && hasStock(command.tenantId(), quantities);
                if (!inventoryCanBeApplied && !command.allowUnallocatedOrders()) {
                    markAll(group, ImportRowStatus.ERROR, null,
                        fullyMapped ? (command.applyInventory() ? "INSUFFICIENT_STOCK" : "UNALLOCATED_ORDERS_DISABLED")
                            : "PRODUCT_MAPPING_REQUIRED");
                    continue;
                }

                UUID orderId = createProductOrder(command, entry.getKey(), representative, orderCurrency, lines,
                    inventoryCanBeApplied);
                insertOrderItems(command.tenantId(), orderId, lines);
                if (inventoryCanBeApplied) {
                    for (Map.Entry<UUID, Integer> quantity : quantities.entrySet()) {
                        inventory.apply(command.tenantId(), quantity.getKey(), -quantity.getValue(),
                            MovementType.SUMUP_IMPORT, orderId, command.batchId(),
                            "SUMUP-" + entry.getKey(), "Imported SumUp order", command.actorId());
                    }
                }
                linkExternalOrder(external.id(), command.tenantId(), orderId);
                markAll(group, statusFor(external.outcome()), orderId, null);
            } catch (RowProblem problem) {
                markAll(group, ImportRowStatus.ERROR, null, problem.code());
            }
        }
    }

    private void confirmProductSummary(ConfirmCommand command, Batch batch, TenantDefaults defaults, List<Row> rows) {
        Map<UUID, ProductSnapshot> products = products(command.tenantId(), rows);
        List<Row> summaryRows = new ArrayList<>(rows.size());
        for (Row row : rows) {
            try {
                positiveQuantity(row);
                ProductSnapshot product = row.linkedProductId() == null ? null : products.get(row.linkedProductId());
                if (product != null && !product.currency().equalsIgnoreCase(
                    currency(row.normalized(), defaults.currency()))) {
                    throw new RowProblem("CURRENCY_MISMATCH");
                }
                summaryRows.add(row);
            } catch (RowProblem problem) {
                mark(row, ImportRowStatus.ERROR, null, problem.code());
            }
        }
        Map<UUID, Integer> quantities = new TreeMap<>();
        boolean applyEligible = command.applyInventory();
        for (Row row : summaryRows) {
            int quantity = positiveQuantity(row);
            ProductSnapshot product = row.linkedProductId() == null ? null : products.get(row.linkedProductId());
            if (command.applyInventory() && product == null) applyEligible = false;
            if (product != null) quantities.merge(product.id(), quantity, DefaultSumUpImportCommitter::addQuantity);
        }
        if (applyEligible) applyEligible = hasStock(command.tenantId(), quantities);
        if (applyEligible) {
            for (Map.Entry<UUID, Integer> quantity : quantities.entrySet()) {
                inventory.apply(command.tenantId(), quantity.getKey(), -quantity.getValue(),
                    MovementType.SUMUP_IMPORT, null, command.batchId(), "SUMUP-PRODUCT-SUMMARY",
                    "Confirmed SumUp product sales summary", command.actorId());
            }
        }

        for (Row row : summaryRows) {
            try {
                int quantity = positiveQuantity(row);
                ProductSnapshot product = row.linkedProductId() == null ? null : products.get(row.linkedProductId());
                Instant period = instant(row.normalized(), "occurredAt").orElse(null);
                BigDecimal gross = nullableAmount(row.normalized(), "grossRevenue", "revenue", "amount");
                BigDecimal net = nullableAmount(row.normalized(), "netRevenue");
                jdbc.update("""
                    insert into imported_sales_summaries
                      (id, tenant_id, import_batch_id, product_id, external_product_name,
                       period_start, period_end, quantity, gross_amount, net_amount, currency,
                       inventory_applied, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    """, UUID.randomUUID(), command.tenantId(), command.batchId(),
                    product == null ? null : product.id(), text(row.normalized(), "productName", 500),
                    timestamp(period), timestamp(period), quantity, gross, net,
                    currency(row.normalized(), defaults.currency()), applyEligible);
                if (command.applyInventory() && !applyEligible) {
                    mark(row, ImportRowStatus.ERROR, null, product == null
                        ? "PRODUCT_MAPPING_REQUIRED" : "PRODUCT_SUMMARY_INVENTORY_NOT_APPLIED");
                } else {
                    mark(row, ImportRowStatus.IMPORTED, null, null);
                }
            } catch (RowProblem problem) {
                mark(row, ImportRowStatus.ERROR, null, problem.code());
            }
        }
    }

    private void confirmAccountingSummary(ConfirmCommand command, TenantDefaults defaults, List<Row> rows) {
        for (Row row : rows) {
            try {
                Instant occurredAt = instant(row.normalized(), "occurredAt").orElse(null);
                LocalDate summaryDate = occurredAt == null ? null
                    : occurredAt.atZone(defaults.zoneId()).toLocalDate();
                jdbc.update("""
                    insert into imported_accounting_summaries
                      (id, tenant_id, import_batch_id, summary_date, payment_method, tax_rate,
                       gross_amount, tax_amount, fee_amount, net_amount, currency, raw_data, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), now())
                    """, UUID.randomUUID(), command.tenantId(), command.batchId(), summaryDate,
                    text(row.normalized(), "paymentMethod", 80), null,
                    nullableAmount(row.normalized(), "grossRevenue", "amount"),
                    nullableAmount(row.normalized(), "tax"), nullableAmount(row.normalized(), "feeAmount"),
                    nullableAmount(row.normalized(), "netRevenue", "netAmount"),
                    currency(row.normalized(), defaults.currency()), writeJson(row.raw()));
                mark(row, ImportRowStatus.IMPORTED, null, null);
            } catch (RowProblem problem) {
                mark(row, ImportRowStatus.ERROR, null, problem.code());
            }
        }
    }

    private ExternalUpsert upsertExternal(ConfirmCommand command, Batch batch,
                                          TenantDefaults defaults, Row row) {
        String transactionId = text(row.normalized(), "transactionId", 200);
        String fingerprint = row.fingerprint();
        if (fingerprint == null || fingerprint.isBlank()) {
            fingerprint = sha256(command.tenantId() + "|" + command.batchId() + "|" + row.rowNumber()
                + "|" + writeJson(row.normalized()));
        }
        Optional<ExistingExternal> existing = Optional.empty();
        if (transactionId != null) {
            existing = findExternal("provider_transaction_id = ?", command.tenantId(), transactionId);
        }
        if (existing.isEmpty()) {
            existing = findExternal("fingerprint = ?", command.tenantId(), fingerprint);
        }

        Instant occurredAt = instant(row.normalized(), "occurredAt").orElse(batch.createdAt());
        BigDecimal amount = amount(row.normalized(), "amount", "grossRevenue", "revenue", "netRevenue");
        String transactionType = enumText(row.normalized(), "type", "SALE", 32);
        String transactionStatus = enumText(row.normalized(), "status", "SUCCESSFUL", 32);
        String rawJson = writeJson(row.raw());
        if (existing.isPresent()) {
            ExistingExternal found = existing.get();
            jdbc.update("""
                update external_transactions
                   set provider_transaction_id = coalesce(provider_transaction_id, ?),
                       provider_transaction_code = ?, provider_merchant_code = ?, transaction_type = ?,
                       transaction_status = ?, occurred_at = ?, amount = ?, currency = ?, fee_amount = ?,
                       net_amount = ?, refund_amount = ?, payment_method = ?, card_type = ?, payout_reference = ?,
                       payout_date = ?, description = ?, raw_data = cast(? as jsonb), updated_at = now()
                 where tenant_id = ? and id = ?
                """, transactionId, text(row.normalized(), "transactionCode", 200),
                text(row.normalized(), "merchant", 200), transactionType, transactionStatus,
                timestamp(occurredAt), amount, currency(row.normalized(), defaults.currency()),
                nullableAmount(row.normalized(), "feeAmount"), nullableAmount(row.normalized(), "netAmount"),
                nullableAmount(row.normalized(), "refundAmount"), text(row.normalized(), "paymentMethod", 32),
                text(row.normalized(), "cardType", 80), text(row.normalized(), "payoutReference", 240),
                timestamp(instant(row.normalized(), "payoutDate").orElse(null)),
                text(row.normalized(), "description", 2000), rawJson, command.tenantId(), found.id());
            UpsertOutcome outcome = found.importBatchId().equals(command.batchId()) || !found.active()
                ? UpsertOutcome.DUPLICATE : UpsertOutcome.UPDATED;
            return new ExternalUpsert(found.id(), found.linkedOrderId(), outcome, found.active());
        }

        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into external_transactions
              (id, tenant_id, provider, provider_transaction_id, provider_transaction_code,
               provider_merchant_code, transaction_type, transaction_status, occurred_at, amount, currency,
               fee_amount, net_amount, refund_amount, payment_method, card_type, payout_reference,
               payout_date, description, linked_order_id, import_batch_id, fingerprint, raw_data,
               active, created_at, updated_at)
            values (?, ?, 'SUMUP', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null, ?, ?,
                    cast(? as jsonb), true, now(), now())
            """, id, command.tenantId(), transactionId, text(row.normalized(), "transactionCode", 200),
            text(row.normalized(), "merchant", 200), transactionType, transactionStatus,
            timestamp(occurredAt), amount, currency(row.normalized(), defaults.currency()),
            nullableAmount(row.normalized(), "feeAmount"), nullableAmount(row.normalized(), "netAmount"),
            nullableAmount(row.normalized(), "refundAmount"), text(row.normalized(), "paymentMethod", 32),
            text(row.normalized(), "cardType", 80), text(row.normalized(), "payoutReference", 240),
            timestamp(instant(row.normalized(), "payoutDate").orElse(null)),
            text(row.normalized(), "description", 2000), command.batchId(), fingerprint, rawJson);
        return new ExternalUpsert(id, null, UpsertOutcome.INSERTED, true);
    }

    private Optional<ExistingExternal> findExternal(String predicate, UUID tenantId, String value) {
        List<ExistingExternal> matches = jdbc.query("""
            select id, import_batch_id, linked_order_id, active from external_transactions
             where tenant_id = ? and provider = 'SUMUP' and %s
             for update
            """.formatted(predicate), (rs, rowNum) -> new ExistingExternal(
                (UUID) rs.getObject("id"), (UUID) rs.getObject("import_batch_id"),
                (UUID) rs.getObject("linked_order_id"), rs.getBoolean("active")), tenantId, value);
        return matches.stream().findFirst();
    }

    private UUID createTransactionOrder(ConfirmCommand command, TenantDefaults defaults, Row row,
                                        BigDecimal amount) {
        UUID orderId = UUID.randomUUID();
        Instant orderDate = instant(row.normalized(), "occurredAt").orElseGet(DefaultSumUpImportCommitter::now);
        insertOrder(orderId, command, orderNumber(command.batchId(), "row-" + row.rowNumber()),
            text(row.normalized(), "transactionId", 200), currency(row.normalized(), defaults.currency()),
            amount, ZERO, ZERO, amount, amount, false, orderDate);
        return orderId;
    }

    private UUID createProductOrder(ConfirmCommand command, String groupKey, Row representative,
                                    String orderCurrency, List<Line> lines, boolean inventoryApplied) {
        BigDecimal subtotal = lines.stream().map(Line::subtotal).reduce(ZERO, BigDecimal::add);
        BigDecimal discount = lines.stream().map(Line::discount).reduce(ZERO, BigDecimal::add);
        BigDecimal tax = lines.stream().map(Line::tax).reduce(ZERO, BigDecimal::add);
        BigDecimal total = lines.stream().map(Line::total).reduce(ZERO, BigDecimal::add);
        UUID orderId = UUID.randomUUID();
        insertOrder(orderId, command, orderNumber(command.batchId(), groupKey),
            text(representative.normalized(), "transactionId", 200),
            orderCurrency,
            money(subtotal), money(discount), money(tax), money(total),
            inventoryApplied ? ZERO : money(total), inventoryApplied,
            instant(representative.normalized(), "occurredAt").orElseGet(DefaultSumUpImportCommitter::now));
        return orderId;
    }

    private void insertOrder(UUID orderId, ConfirmCommand command, String number, String externalTransactionId,
                             String currency, BigDecimal subtotal, BigDecimal discount, BigDecimal tax,
                             BigDecimal total, BigDecimal unallocated, boolean inventoryApplied, Instant orderDate) {
        jdbc.update("""
            insert into orders
              (id, tenant_id, order_number, source, external_provider, external_transaction_id,
               status, allocation_status, sales_channel, currency, subtotal, discount_amount, tax_amount,
               refund_amount, total_amount, unallocated_amount, payment_method, payment_status, order_date,
               inventory_applied, manually_modified_after_import, import_batch_id, created_by,
               created_at, updated_at, version)
            values (?, ?, ?, 'SUMUP_IMPORT', 'SUMUP', ?, 'COMPLETED', ?, 'SUMUP', ?, ?, ?, ?, ?, ?, ?,
                    'SUMUP', 'PAID', ?, ?, false, ?, ?, now(), now(), 0)
            """, orderId, command.tenantId(), number, externalTransactionId,
            inventoryApplied ? "FULLY_ALLOCATED" : "UNALLOCATED", currency,
            subtotal, discount, tax, ZERO, total, unallocated, timestamp(orderDate), inventoryApplied,
            command.batchId(), command.actorId());
        jdbc.update("""
            insert into payments
              (id, tenant_id, order_id, provider, provider_transaction_id, amount, currency,
               payment_method, status, paid_at, created_at, updated_at)
            values (?, ?, ?, 'SUMUP', ?, ?, ?, 'SUMUP', 'PAID', ?, now(), now())
            """, UUID.randomUUID(), command.tenantId(), orderId, externalTransactionId, total, currency,
            timestamp(orderDate));
    }

    private void applyImportedRefund(ConfirmCommand command, UUID externalId, UUID orderId, List<Row> rows) {
        OrderState order = lockedOrder(command.tenantId(), orderId);
        if (order.status().equals("CANCELLED") || order.status().equals("DRAFT")) {
            throw new RowProblem("ORDER_NOT_REFUNDABLE");
        }
        BigDecimal desired = desiredRefund(rows, order);
        BigDecimal current = money(order.refundAmount());
        if (desired.compareTo(current) < 0) desired = current;
        desired = desired.min(money(order.totalAmount()));
        boolean full = desired.compareTo(money(order.totalAmount())) >= 0;
        String status = full ? "REFUNDED" : "PARTIALLY_REFUNDED";

        jdbc.update("""
            update external_transactions
               set amount = ?, refund_amount = ?, transaction_status = ?, updated_at = now()
             where tenant_id = ? and id = ? and linked_order_id = ?
            """, money(order.totalAmount()), desired, status, command.tenantId(), externalId, orderId);
        ensureRefundedPayment(command.tenantId(), order, status);
        jdbc.update("""
            update orders
               set refund_amount = ?, status = ?, payment_status = ?, updated_at = now(), version = version + 1
             where tenant_id = ? and id = ?
            """, desired, status, status, command.tenantId(), orderId);

        BigDecimal delta = money(desired.subtract(current));
        if (delta.signum() <= 0) return;

        UUID refundId = UUID.randomUUID();
        String reason = "SUMUP_IMPORT_BATCH:" + command.batchId();
        jdbc.update("""
            insert into order_refunds (id, tenant_id, order_id, amount, reason, created_by, created_at)
            values (?, ?, ?, ?, ?, ?, now())
            """, refundId, command.tenantId(), orderId, delta, reason, command.actorId());

        List<RefundAllocation> allocations = refundAllocations(command.tenantId(), orderId, rows);
        BigDecimal allocatedAmount = allocations.stream().map(RefundAllocation::amount)
            .reduce(ZERO, BigDecimal::add);
        // A malformed file must never return more stock than its new financial
        // refund can explain. A small tolerance covers normal currency rounding.
        if (allocatedAmount.compareTo(delta.add(new BigDecimal("0.50"))) > 0) allocations = List.of();

        Map<UUID, Integer> stockReturns = new TreeMap<>();
        for (RefundAllocation allocation : allocations) {
            jdbc.update("""
                update order_items set refunded_quantity = refunded_quantity + ?, updated_at = now()
                 where tenant_id = ? and order_id = ? and id = ?
                   and refunded_quantity + ? <= quantity
                """, allocation.quantity(), command.tenantId(), orderId, allocation.itemId(), allocation.quantity());
            jdbc.update("""
                insert into order_refund_items
                  (id, tenant_id, refund_id, order_item_id, quantity, amount, created_at)
                values (?, ?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), command.tenantId(), refundId, allocation.itemId(),
                allocation.quantity(), allocation.amount());
            if (order.inventoryApplied() && allocation.productId() != null) {
                stockReturns.merge(allocation.productId(), allocation.quantity(),
                    DefaultSumUpImportCommitter::addQuantity);
            }
        }
        if (!hasOriginalInventoryMovement(command.tenantId(), orderId)) stockReturns.clear();
        for (Map.Entry<UUID, Integer> stockReturn : stockReturns.entrySet()) {
            inventory.apply(command.tenantId(), stockReturn.getKey(), stockReturn.getValue(),
                MovementType.ORDER_REFUND, orderId, command.batchId(), order.orderNumber(),
                "Imported SumUp refund", command.actorId());
        }

    }

    private OrderState lockedOrder(UUID tenantId, UUID orderId) {
        List<OrderState> orders = jdbc.query("""
            select id, total_amount, refund_amount, status, payment_status, inventory_applied,
                   order_number, currency, external_transaction_id, order_date
              from orders where tenant_id = ? and id = ? for update
            """, (rs, rowNum) -> new OrderState((UUID) rs.getObject("id"),
            rs.getBigDecimal("total_amount"), rs.getBigDecimal("refund_amount"), rs.getString("status"),
            rs.getString("payment_status"), rs.getBoolean("inventory_applied"), rs.getString("order_number"),
            rs.getString("currency"), rs.getString("external_transaction_id"),
            rs.getTimestamp("order_date").toInstant()), tenantId, orderId);
        if (orders.isEmpty()) throw new RowProblem("LINKED_ORDER_NOT_FOUND");
        return orders.getFirst();
    }

    private BigDecimal desiredRefund(List<Row> rows, OrderState order) {
        boolean full = rows.stream().anyMatch(row -> {
            String status = enumText(row.normalized(), "status", "", 32);
            String type = enumText(row.normalized(), "type", "", 32);
            return status.equals("REFUNDED") || status.equals("FULLY_REFUNDED")
                || type.equals("CHARGEBACK") || type.equals("CHARGE_BACK");
        });
        if (full) return money(order.totalAmount());

        List<BigDecimal> explicit = rows.stream()
            .map(row -> nullableAmount(row.normalized(), "refundAmount"))
            .filter(Objects::nonNull).map(BigDecimal::abs).filter(value -> value.signum() > 0).toList();
        BigDecimal target = null;
        if (!explicit.isEmpty()) {
            boolean repeatedTotal = explicit.stream().allMatch(value -> value.compareTo(explicit.getFirst()) == 0);
            target = repeatedTotal ? explicit.getFirst() : explicit.stream().reduce(ZERO, BigDecimal::add);
        } else if (rows.stream().anyMatch(row -> {
            String type = enumText(row.normalized(), "type", "", 32);
            return type.equals("REFUND") || type.equals("REVERSAL");
        })) {
            target = rows.stream().map(row -> nullableAmount(row.normalized(), "amount"))
                .filter(Objects::nonNull).map(BigDecimal::abs).reduce(ZERO, BigDecimal::add);
        }
        if (target == null || target.signum() <= 0) throw new RowProblem("REFUND_AMOUNT_REQUIRED");
        return money(target);
    }

    private List<RefundAllocation> refundAllocations(UUID tenantId, UUID orderId, List<Row> rows) {
        Map<UUID, Integer> requested = new TreeMap<>();
        for (Row row : rows) {
            if (row.linkedProductId() == null || !row.normalized().containsKey("quantity")) continue;
            try {
                requested.merge(row.linkedProductId(), positiveQuantity(row),
                    DefaultSumUpImportCommitter::addQuantity);
            } catch (RowProblem ignored) {
                // Financial status is still useful, but ambiguous product data
                // must never result in a stock movement.
            }
        }
        if (requested.isEmpty()) return List.of();
        List<OrderItemState> items = jdbc.query("""
            select id, product_id, quantity, refunded_quantity, line_total
              from order_items where tenant_id = ? and order_id = ? order by created_at, id for update
            """, (rs, rowNum) -> new OrderItemState((UUID) rs.getObject("id"),
            (UUID) rs.getObject("product_id"), rs.getInt("quantity"), rs.getInt("refunded_quantity"),
            rs.getBigDecimal("line_total")), tenantId, orderId);
        List<RefundAllocation> allocations = new ArrayList<>();
        for (Map.Entry<UUID, Integer> request : requested.entrySet()) {
            int remainingRequest = request.getValue();
            for (OrderItemState item : items) {
                if (remainingRequest == 0 || !request.getKey().equals(item.productId())) continue;
                int remainingItem = item.quantity() - item.refundedQuantity();
                if (remainingItem <= 0) continue;
                int quantity = Math.min(remainingRequest, remainingItem);
                BigDecimal previous = item.lineTotal().multiply(BigDecimal.valueOf(item.refundedQuantity()))
                    .divide(BigDecimal.valueOf(item.quantity()), 4, RoundingMode.HALF_UP);
                BigDecimal after = item.lineTotal().multiply(
                        BigDecimal.valueOf(item.refundedQuantity() + quantity))
                    .divide(BigDecimal.valueOf(item.quantity()), 4, RoundingMode.HALF_UP);
                allocations.add(new RefundAllocation(item.id(), item.productId(), quantity,
                    money(after.subtract(previous))));
                remainingRequest -= quantity;
            }
        }
        return allocations;
    }

    private void ensureRefundedPayment(UUID tenantId, OrderState order, String status) {
        int updated = jdbc.update("""
            update payments set status = ?, updated_at = now()
             where tenant_id = ? and order_id = ?
            """, status, tenantId, order.id());
        if (updated == 0) {
            jdbc.update("""
                insert into payments
                  (id, tenant_id, order_id, provider, provider_transaction_id, amount, currency,
                   payment_method, status, paid_at, created_at, updated_at)
                values (?, ?, ?, 'SUMUP', ?, ?, ?, 'SUMUP', ?, ?, now(), now())
                """, UUID.randomUUID(), tenantId, order.id(), order.externalTransactionId(),
                money(order.totalAmount()), order.currency(), status, timestamp(order.orderDate()));
        }
    }

    private boolean hasOriginalInventoryMovement(UUID tenantId, UUID orderId) {
        Integer count = jdbc.queryForObject("""
            select count(*) from inventory_movements
             where tenant_id = ? and related_order_id = ? and movement_type = 'SUMUP_IMPORT'
            """, Integer.class, tenantId, orderId);
        return count != null && count > 0;
    }

    private List<Line> orderLines(UUID tenantId, List<Row> group, String orderCurrency) {
        Map<UUID, ProductSnapshot> products = products(tenantId, group);
        List<Line> result = new ArrayList<>(group.size());
        for (Row row : group) {
            int quantity = positiveQuantity(row);
            ProductSnapshot product = row.linkedProductId() == null ? null : products.get(row.linkedProductId());
            if (product != null && !product.currency().equalsIgnoreCase(orderCurrency)) {
                throw new RowProblem("CURRENCY_MISMATCH");
            }
            BigDecimal explicitTotal = nullableAmount(row.normalized(), "revenue", "grossRevenue", "amount", "netRevenue");
            BigDecimal unit = nullableAmount(row.normalized(), "unitPrice");
            if (unit == null) {
                unit = explicitTotal == null ? (product == null ? ZERO : money(product.salePrice()))
                    : explicitTotal.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP);
            }
            BigDecimal discount = Optional.ofNullable(nullableAmount(row.normalized(), "discount")).orElse(ZERO);
            BigDecimal tax = Optional.ofNullable(nullableAmount(row.normalized(), "tax")).orElse(ZERO);
            BigDecimal subtotal = money(unit.multiply(BigDecimal.valueOf(quantity)));
            BigDecimal total = explicitTotal == null ? money(subtotal.subtract(discount).add(tax)) : money(explicitTotal);
            if (unit.signum() < 0 || discount.signum() < 0 || tax.signum() < 0 || total.signum() < 0) {
                throw new RowProblem("INVALID_ORDER_AMOUNT");
            }
            String sku = product == null ? text(row.normalized(), "sku", 100) : product.sku();
            String name = product == null ? text(row.normalized(), "productName", 240) : product.name();
            if (name == null || name.isBlank()) name = "Unallocated SumUp item";
            result.add(new Line(row, product, sku, name, money(unit), quantity, money(discount), money(tax),
                subtotal, total));
        }
        return result;
    }

    private void insertOrderItems(UUID tenantId, UUID orderId, List<Line> lines) {
        for (Line line : lines) {
            jdbc.update("""
                insert into order_items
                  (id, tenant_id, order_id, product_id, product_sku_snapshot, product_name_snapshot,
                   unit_price, quantity, discount_amount, tax_rate, tax_amount, line_total,
                   refunded_quantity, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, now(), now())
                """, UUID.randomUUID(), tenantId, orderId,
                line.product() == null ? null : line.product().id(), line.sku(), line.name(), line.unitPrice(),
                line.quantity(), line.discount(), ZERO, line.tax(), line.total());
        }
    }

    private Map<UUID, ProductSnapshot> products(UUID tenantId, List<Row> rows) {
        Set<UUID> ids = new LinkedHashSet<>();
        rows.stream().map(Row::linkedProductId).filter(Objects::nonNull).forEach(ids::add);
        Map<UUID, ProductSnapshot> result = new LinkedHashMap<>();
        for (UUID id : ids.stream().sorted().toList()) {
            jdbc.query("""
                select id, sku, name, sale_price, currency, current_stock from products
                 where tenant_id = ? and id = ?
                """, rs -> {
                    if (rs.next()) {
                        ProductSnapshot product = new ProductSnapshot((UUID) rs.getObject("id"),
                            rs.getString("sku"), rs.getString("name"), rs.getBigDecimal("sale_price"),
                            rs.getString("currency"), rs.getInt("current_stock"));
                        result.put(product.id(), product);
                    }
                    return null;
                }, tenantId, id);
        }
        return result;
    }

    private boolean hasStock(UUID tenantId, Map<UUID, Integer> quantities) {
        for (Map.Entry<UUID, Integer> requirement : quantities.entrySet().stream()
            .sorted(Map.Entry.comparingByKey()).toList()) {
            List<Integer> stocks = jdbc.query("""
                select current_stock from products where tenant_id = ? and id = ? for update
                """, (rs, rowNum) -> rs.getInt(1), tenantId, requirement.getKey());
            if (stocks.isEmpty() || stocks.getFirst() < requirement.getValue()) return false;
        }
        return true;
    }

    private Map<UUID, Integer> quantities(List<Line> lines) {
        Map<UUID, Integer> result = new TreeMap<>();
        for (Line line : lines) {
            if (line.product() != null) {
                result.merge(line.product().id(), line.quantity(), DefaultSumUpImportCommitter::addQuantity);
            }
        }
        return result;
    }

    private static int addQuantity(int left, int right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new RowProblem("INVALID_QUANTITY");
        }
    }

    private String singleCurrency(List<Row> group, String defaultCurrency) {
        Set<String> currencies = new LinkedHashSet<>();
        for (Row row : group) {
            currencies.add(currency(row.normalized(), defaultCurrency));
        }
        if (currencies.size() > 1) throw new RowProblem("MIXED_ORDER_CURRENCIES");
        return currencies.iterator().next();
    }

    private void linkExternalOrder(UUID externalId, UUID tenantId, UUID orderId) {
        jdbc.update("""
            update external_transactions set linked_order_id = ?, updated_at = now()
             where tenant_id = ? and id = ? and linked_order_id is null
            """, orderId, tenantId, externalId);
    }

    private void markAll(List<Row> rows, ImportRowStatus status, UUID orderId, String error) {
        rows.forEach(row -> mark(row, status, orderId, error));
    }

    private void mark(Row row, ImportRowStatus status, UUID orderId, String error) {
        if (error == null) {
            jdbc.update("""
                update import_rows set processing_status = ?, linked_order_id = ?
                 where tenant_id = ? and import_batch_id = ? and id = ?
                """, status.name(), orderId, row.tenantId(), row.batchId(), row.id());
        } else {
            jdbc.update("""
                update import_rows
                   set processing_status = ?, linked_order_id = ?,
                       validation_errors = validation_errors || jsonb_build_array(cast(? as text))
                 where tenant_id = ? and import_batch_id = ? and id = ?
                """, status.name(), orderId, error, row.tenantId(), row.batchId(), row.id());
        }
    }

    private Result resultFor(UUID tenantId, UUID batchId) {
        Map<String, Integer> statuses = new LinkedHashMap<>();
        jdbc.query("""
            select processing_status, count(*)::integer as count from import_rows
             where tenant_id = ? and import_batch_id = ? group by processing_status
            """, rs -> {
                while (rs.next()) statuses.put(rs.getString("processing_status"), rs.getInt("count"));
                return null;
            }, tenantId, batchId);
        int orders = count("orders", tenantId, batchId);
        Integer movements = jdbc.queryForObject("""
            select count(*) from inventory_movements
             where tenant_id = ? and related_import_batch_id = ?
               and movement_type in ('SUMUP_IMPORT', 'ORDER_REFUND')
            """, Integer.class, tenantId, batchId);
        return new Result(statuses.getOrDefault(ImportRowStatus.IMPORTED.name(), 0),
            statuses.getOrDefault(ImportRowStatus.UPDATED.name(), 0),
            statuses.getOrDefault(ImportRowStatus.DUPLICATE.name(), 0),
            statuses.getOrDefault(ImportRowStatus.ERROR.name(), 0), orders, movements == null ? 0 : movements);
    }

    private int rowCount(UUID tenantId, UUID batchId, ImportRowStatus status) {
        Integer count = jdbc.queryForObject("""
            select count(*) from import_rows
             where tenant_id = ? and import_batch_id = ? and processing_status = ?
            """, Integer.class, tenantId, batchId, status.name());
        return count == null ? 0 : count;
    }

    private int count(String table, UUID tenantId, UUID batchId) {
        // table is an internal constant selected by the caller, never user input.
        Integer count = jdbc.queryForObject("select count(*) from " + table
            + " where tenant_id = ? and import_batch_id = ?", Integer.class, tenantId, batchId);
        return count == null ? 0 : count;
    }

    private List<Row> analyzedRows(UUID tenantId, UUID batchId) {
        return jdbc.query("""
            select id, tenant_id, import_batch_id, row_number, external_transaction_id, fingerprint,
                   normalized_data::text, sanitized_raw_data::text, linked_product_id
              from import_rows
             where tenant_id = ? and import_batch_id = ? and processing_status = 'VALID'
             order by row_number
            """, (rs, rowNum) -> new Row((UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"),
            (UUID) rs.getObject("import_batch_id"), rs.getInt("row_number"),
            rs.getString("external_transaction_id"), rs.getString("fingerprint"),
            readJson(rs.getString("normalized_data")), readJson(rs.getString("sanitized_raw_data")),
            (UUID) rs.getObject("linked_product_id")), tenantId, batchId);
    }

    private Batch lockedBatch(UUID batchId, UUID tenantId) {
        List<Batch> batches = jdbc.query("""
            select id, tenant_id, import_type, status, analysis_version, created_at,
                   imported_rows, updated_rows, duplicate_rows, error_rows, order_count
              from import_batches where id = ? and tenant_id = ? for update
            """, (rs, rowNum) -> new Batch((UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"),
            ImportType.valueOf(rs.getString("import_type")), ImportBatchStatus.valueOf(rs.getString("status")),
            rs.getInt("analysis_version"), rs.getTimestamp("created_at").toInstant(),
            rs.getInt("imported_rows"), rs.getInt("updated_rows"), rs.getInt("duplicate_rows"),
            rs.getInt("error_rows"), rs.getInt("order_count")), batchId, tenantId);
        if (batches.isEmpty()) throw new NotFoundException("Import batch");
        return batches.getFirst();
    }

    private void lockTenant(UUID tenantId) {
        List<UUID> tenants = jdbc.query("select id from tenants where id = ? for update",
            (rs, rowNum) -> (UUID) rs.getObject(1), tenantId);
        if (tenants.isEmpty()) throw new NotFoundException("Tenant");
    }

    private TenantDefaults tenantDefaults(UUID tenantId) {
        return jdbc.queryForObject("select default_currency, timezone from tenants where id = ?",
            (rs, rowNum) -> new TenantDefaults(currency(rs.getString("default_currency"), "EUR"),
                safeZone(rs.getString("timezone"))), tenantId);
    }

    private void verifyActor(UUID actorId, UUID tenantId) {
        Integer count = jdbc.queryForObject("""
            select count(*) from users
             where id = ? and enabled = true and (tenant_id = ? or role = 'ADMIN')
            """, Integer.class, actorId, tenantId);
        if (count == null || count == 0) {
            throw new BusinessException("IMPORT_ACTOR_INVALID",
                "The importing user is not active for this tenant", HttpStatus.FORBIDDEN);
        }
    }

    private void refuseUnsafeReversal(ReverseCommand command) {
        Integer importedRefunds = jdbc.queryForObject("""
            select count(*) from order_refunds
             where tenant_id = ? and reason = ?
            """, Integer.class, command.tenantId(), "SUMUP_IMPORT_BATCH:" + command.batchId());
        if (importedRefunds != null && importedRefunds > 0) {
            throw new BusinessException("IMPORT_REVERSAL_CONFLICT",
                "This batch changed an existing order refund and requires manual reconciliation",
                HttpStatus.CONFLICT);
        }
        Integer conflicts = jdbc.queryForObject("""
            select count(*)
              from orders o
             where o.tenant_id = ? and o.import_batch_id = ? and o.source = 'SUMUP_IMPORT'
               and (o.manually_modified_after_import = true
                    or o.status not in ('CONFIRMED', 'COMPLETED')
                    or exists (select 1 from order_refunds r
                                where r.tenant_id = o.tenant_id and r.order_id = o.id)
                    or (o.inventory_applied = false and exists (
                        select 1 from inventory_movements m
                         where m.tenant_id = o.tenant_id and m.related_order_id = o.id
                           and m.related_import_batch_id = ? and m.movement_type = 'SUMUP_IMPORT')))
            """, Integer.class, command.tenantId(), command.batchId(), command.batchId());
        if (conflicts != null && conflicts > 0) {
            throw new BusinessException("IMPORT_REVERSAL_CONFLICT",
                "Imported orders were modified, cancelled or refunded and require manual reconciliation",
                HttpStatus.CONFLICT);
        }
        Integer priorReversals = jdbc.queryForObject("""
            select count(*) from inventory_movements
             where tenant_id = ? and related_import_batch_id = ? and movement_type = 'SUMUP_REVERSAL'
            """, Integer.class, command.tenantId(), command.batchId());
        if (priorReversals != null && priorReversals > 0) {
            throw new BusinessException("IMPORT_REVERSAL_CONFLICT",
                "This import already has stock reversal movements", HttpStatus.CONFLICT);
        }
    }

    private static void requireConfirmCommand(ConfirmCommand command) {
        if (command == null || command.tenantId() == null || command.actorId() == null || command.batchId() == null
            || command.analysisVersion() < 0) {
            throw new BusinessException("INVALID_IMPORT_CONFIRMATION", "Tenant, actor, batch and version are required");
        }
    }

    private static void requireReverseCommand(ReverseCommand command) {
        if (command == null || command.tenantId() == null || command.actorId() == null || command.batchId() == null) {
            throw new BusinessException("INVALID_IMPORT_REVERSAL", "Tenant, actor and batch are required");
        }
    }

    private static String orderGroup(Row row) {
        String code = text(row.normalized(), "transactionCode", 200);
        if (code != null) return "code:" + code;
        String id = text(row.normalized(), "transactionId", 200);
        if (id != null) return "transaction:" + id;
        return "row:" + row.rowNumber();
    }

    private static ImportRowStatus statusFor(UpsertOutcome outcome) {
        return switch (outcome) {
            case INSERTED -> ImportRowStatus.IMPORTED;
            case UPDATED -> ImportRowStatus.UPDATED;
            case DUPLICATE -> ImportRowStatus.DUPLICATE;
        };
    }

    private static boolean isSuccessfulSale(Map<String, Object> data) {
        String status = enumText(data, "status", "", 32);
        String type = enumText(data, "type", "SALE", 32);
        return SUCCESS_STATUSES.contains(status) && !NON_SALE_TYPES.contains(type);
    }

    private static boolean isRefundUpdate(Map<String, Object> data) {
        String status = enumText(data, "status", "", 32);
        String type = enumText(data, "type", "", 32);
        return status.equals("REFUNDED") || status.equals("FULLY_REFUNDED")
            || status.equals("PARTIALLY_REFUNDED") || status.equals("PARTIAL_REFUND")
            || type.equals("REFUND") || type.equals("REFUNDED") || type.equals("REVERSAL")
            || type.equals("CHARGEBACK") || type.equals("CHARGE_BACK");
    }

    private static int positiveQuantity(Row row) {
        Object value = row.normalized().get("quantity");
        if (value == null) throw new RowProblem("MISSING_QUANTITY");
        try {
            int quantity = value instanceof Number number ? new BigDecimal(number.toString()).intValueExact()
                : new BigDecimal(value.toString().trim()).intValueExact();
            if (quantity <= 0) throw new RowProblem("INVALID_QUANTITY");
            return quantity;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new RowProblem("INVALID_QUANTITY");
        }
    }

    private static BigDecimal amount(Map<String, Object> data, String... fields) {
        BigDecimal amount = nullableAmount(data, fields);
        return amount == null ? ZERO : amount;
    }

    private static BigDecimal nullableAmount(Map<String, Object> data, String... fields) {
        for (String field : fields) {
            Object value = data.get(field);
            if (value == null || value.toString().isBlank()) continue;
            try {
                return money(new BigDecimal(value.toString().trim()));
            } catch (NumberFormatException exception) {
                throw new RowProblem("INVALID_AMOUNT");
            }
        }
        return null;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static Optional<Instant> instant(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (value == null || value.toString().isBlank()) return Optional.empty();
        try {
            return Optional.of(Instant.parse(value.toString().trim()));
        } catch (DateTimeException exception) {
            return Optional.empty();
        }
    }

    private static String currency(Map<String, Object> data, String fallback) {
        Object value = data.get("currency");
        return currency(value == null ? null : value.toString(), fallback);
    }

    private static String currency(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z]{3}") ? normalized : fallback;
    }

    private static String enumText(Map<String, Object> data, String field, String fallback, int max) {
        String value = text(data, field, max);
        if (value == null) return fallback;
        return value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private static String text(Map<String, Object> data, String field, int max) {
        Object value = data.get(field);
        if (value == null) return null;
        String result = value.toString().trim();
        if (result.isBlank()) return null;
        return result.substring(0, Math.min(result.length(), max));
    }

    private static ZoneId safeZone(String value) {
        try {
            return ZoneId.of(value == null ? "UTC" : value);
        } catch (DateTimeException exception) {
            return ZoneId.of("UTC");
        }
    }

    private Map<String, Object> readJson(String source) {
        try {
            return json.readValue(source, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored import row JSON is invalid", exception);
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Import row JSON cannot be serialized", exception);
        }
    }

    private static String orderNumber(UUID batchId, String group) {
        UUID hash = UUID.nameUUIDFromBytes((batchId + "|" + group).getBytes(StandardCharsets.UTF_8));
        return ("SUMUP-" + batchId.toString().substring(0, 8) + "-" + hash.toString().substring(0, 12))
            .toUpperCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant now() {
        return Instant.now();
    }

    private enum UpsertOutcome { INSERTED, UPDATED, DUPLICATE }

    private record Batch(UUID id, UUID tenantId, ImportType importType, ImportBatchStatus status,
                         int analysisVersion, Instant createdAt, int importedRows, int updatedRows,
                         int duplicateRows, int errorRows, int orderCount) {}

    private record Row(UUID id, UUID tenantId, UUID batchId, int rowNumber, String externalTransactionId,
                       String fingerprint, Map<String, Object> normalized, Map<String, Object> raw,
                       UUID linkedProductId) {}

    private record TenantDefaults(String currency, ZoneId zoneId) {}

    private record ExistingExternal(UUID id, UUID importBatchId, UUID linkedOrderId, boolean active) {}

    private record ExternalUpsert(UUID id, UUID linkedOrderId, UpsertOutcome outcome, boolean active) {}

    private record OrderState(UUID id, BigDecimal totalAmount, BigDecimal refundAmount, String status,
                              String paymentStatus, boolean inventoryApplied, String orderNumber,
                              String currency, String externalTransactionId, Instant orderDate) {}

    private record OrderItemState(UUID id, UUID productId, int quantity, int refundedQuantity,
                                  BigDecimal lineTotal) {}

    private record RefundAllocation(UUID itemId, UUID productId, int quantity, BigDecimal amount) {}

    private record ProductSnapshot(UUID id, String sku, String name, BigDecimal salePrice,
                                   String currency, int stock) {}

    private record Line(Row row, ProductSnapshot product, String sku, String name, BigDecimal unitPrice,
                        int quantity, BigDecimal discount, BigDecimal tax, BigDecimal subtotal,
                        BigDecimal total) {}

    private record StockDelta(UUID productId, long quantity) {}

    private static final class RowProblem extends RuntimeException {
        private final String code;

        private RowProblem(String code) {
            super(code, null, false, false);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}
