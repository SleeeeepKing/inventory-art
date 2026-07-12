package com.inventoryart.sumup;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    properties = {
      "app.seed.enabled=false",
      "app.storage.provider=local",
      "app.storage.local-path=target/test-storage"
    })
class DefaultSumUpImportCommitterIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired SumUpImportCommitter committer;
  @Autowired JdbcTemplate jdbc;

  @Test
  void commitsAndReversesMappedOrderWithoutChangingInventory() {
    UUID tenant = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    UUID product = UUID.randomUUID();
    UUID batch = UUID.randomUUID();
    insertTenantAndActor(tenant, actor);
    jdbc.update(
        """
            insert into products
              (id, tenant_id, sku, name, sale_price, currency, current_stock, low_stock_threshold,
               enabled, version, created_at, updated_at)
            values (?, ?, 'ART-1', 'Limited print', 25.0000, 'EUR', 5, 1, true, 0, now(), now())
            """,
        product,
        tenant);
    insertBatch(tenant, actor, batch, "ORDER_HISTORY");
    jdbc.update(
        """
            insert into import_rows
              (id, tenant_id, import_batch_id, row_number, row_type, processing_status,
               external_transaction_id, fingerprint, normalized_data, sanitized_raw_data,
               validation_errors, linked_product_id, created_at)
            values (?, ?, ?, 1, 'ORDER_HISTORY', 'VALID', 'tx-order-1', ?,
                    cast(? as jsonb), '{}'::jsonb, '[]'::jsonb, ?, now())
            """,
        UUID.randomUUID(),
        tenant,
        batch,
        "a".repeat(64),
        """
                {"transactionId":"tx-order-1","transactionCode":"order-1","status":"SUCCESSFUL",
                 "type":"SALE","occurredAt":"2026-07-01T10:00:00Z","currency":"EUR",
                 "productName":"Limited print","sku":"ART-1","quantity":2,"unitPrice":25,"revenue":50}
                """,
        product);

    SumUpImportCommitter.Result confirmed =
        committer.confirm(new SumUpImportCommitter.ConfirmCommand(tenant, actor, batch, 1));

    assertThat(confirmed.importedRows()).isEqualTo(1);
    assertThat(confirmed.orderCount()).isEqualTo(1);
    assertThat(confirmed.inventoryMovementCount()).isZero();
    assertThat(integer("select current_stock from products where id = ?", product)).isEqualTo(5);
    assertThat(
            integer(
                "select count(*) from order_items where tenant_id = ? and product_id = ?",
                tenant,
                product))
        .isEqualTo(1);
    assertThat(string("select allocation_status from orders where import_batch_id = ?", batch))
        .isEqualTo("FULLY_ALLOCATED");
    assertThat(string("select sales_channel from orders where import_batch_id = ?", batch))
        .isEqualTo("EXHIBITION");
    assertThat(string("select payment_method from orders where import_batch_id = ?", batch))
        .isEqualTo("SUMUP");
    assertThat(
            integer(
                "select count(*) from orders where import_batch_id = ? and event_id is not null",
                batch))
        .isEqualTo(1);
    assertThat(string("select status from import_batches where id = ?", batch))
        .isEqualTo("COMPLETED");

    committer.reverse(new SumUpImportCommitter.ReverseCommand(tenant, actor, batch));

    assertThat(integer("select current_stock from products where id = ?", product)).isEqualTo(5);
    assertThat(string("select status from orders where import_batch_id = ?", batch))
        .isEqualTo("CANCELLED");
    assertThat(
            integer(
                "select count(*) from inventory_movements where related_import_batch_id = ? and movement_type = 'SUMUP_REVERSAL'",
                batch))
        .isZero();
    assertThat(
            Boolean.TRUE.equals(
                jdbc.queryForObject(
                    "select active from external_transactions where import_batch_id = ?",
                    Boolean.class,
                    batch)))
        .isFalse();
    assertThat(string("select status from import_batches where id = ?", batch))
        .isEqualTo("REVERSED");
  }

  @Test
  void rejectsMappedEuroProductFromUsdOrderAndProductSummaryWithoutInventorySideEffects() {
    UUID tenant = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    UUID product = UUID.randomUUID();
    insertTenantAndActor(tenant, actor);
    jdbc.update(
        """
            insert into products
              (id, tenant_id, sku, name, sale_price, currency, current_stock, low_stock_threshold,
               enabled, version, created_at, updated_at)
            values (?, ?, 'EUR-ART', 'Euro print', 25.0000, 'EUR', 8, 1, true, 0, now(), now())
            """,
        product,
        tenant);

    UUID orderBatch = UUID.randomUUID();
    insertBatch(tenant, actor, orderBatch, "ORDER_HISTORY");
    insertCurrencyMismatchRow(tenant, orderBatch, product, "ORDER_HISTORY", "usd-order-1");

    SumUpImportCommitter.Result orderResult =
        committer.confirm(new SumUpImportCommitter.ConfirmCommand(tenant, actor, orderBatch, 1));

    assertThat(orderResult.errorRows()).isEqualTo(1);
    assertThat(orderResult.orderCount()).isZero();
    assertThat(orderResult.inventoryMovementCount()).isZero();
    assertThat(
            string(
                "select processing_status from import_rows where import_batch_id = ?", orderBatch))
        .isEqualTo("ERROR");
    assertThat(
            integer(
                """
            select count(*) from import_rows
             where import_batch_id = ?
               and validation_errors @> '["CURRENCY_MISMATCH"]'::jsonb
            """,
                orderBatch))
        .isEqualTo(1);
    assertThat(
            integer(
                "select count(*) from orders where tenant_id = ? and import_batch_id = ?",
                tenant,
                orderBatch))
        .isZero();
    assertThat(integer("select current_stock from products where id = ?", product)).isEqualTo(8);

    UUID summaryBatch = UUID.randomUUID();
    insertBatch(tenant, actor, summaryBatch, "PRODUCT_SALES");
    insertCurrencyMismatchRow(tenant, summaryBatch, product, "PRODUCT_SALES", null);

    SumUpImportCommitter.Result summaryResult =
        committer.confirm(new SumUpImportCommitter.ConfirmCommand(tenant, actor, summaryBatch, 1));

    assertThat(summaryResult.errorRows()).isEqualTo(1);
    assertThat(summaryResult.orderCount()).isZero();
    assertThat(summaryResult.inventoryMovementCount()).isZero();
    assertThat(
            string(
                "select processing_status from import_rows where import_batch_id = ?",
                summaryBatch))
        .isEqualTo("ERROR");
    assertThat(
            integer(
                """
            select count(*) from import_rows
             where import_batch_id = ?
               and validation_errors @> '["CURRENCY_MISMATCH"]'::jsonb
            """,
                summaryBatch))
        .isEqualTo(1);
    assertThat(
            integer(
                "select count(*) from imported_sales_summaries where import_batch_id = ?",
                summaryBatch))
        .isZero();
    assertThat(integer("select current_stock from products where id = ?", product)).isEqualTo(8);
    assertThat(
            integer(
                "select count(*) from inventory_movements where tenant_id = ? and product_id = ?",
                tenant,
                product))
        .isZero();
  }

  @Test
  void transactionHistoryCreatesAnUnallocatedOrderWithoutTouchingStock() {
    UUID tenant = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    UUID batch = UUID.randomUUID();
    insertTenantAndActor(tenant, actor);
    insertBatch(tenant, actor, batch, "TRANSACTION_HISTORY");
    jdbc.update(
        """
            insert into import_rows
              (id, tenant_id, import_batch_id, row_number, row_type, processing_status,
               external_transaction_id, fingerprint, normalized_data, sanitized_raw_data,
               validation_errors, created_at)
            values (?, ?, ?, 1, 'TRANSACTION_HISTORY', 'VALID', 'tx-financial-1', ?,
                    cast(? as jsonb), '{}'::jsonb, '[]'::jsonb, now())
            """,
        UUID.randomUUID(),
        tenant,
        batch,
        "b".repeat(64),
        """
                {"transactionId":"tx-financial-1","status":"SUCCESSFUL","type":"SALE",
                 "occurredAt":"2026-07-02T12:00:00Z","currency":"EUR","amount":19.95}
                """);

    SumUpImportCommitter.Result result =
        committer.confirm(new SumUpImportCommitter.ConfirmCommand(tenant, actor, batch, 1));

    assertThat(result.importedRows()).isEqualTo(1);
    assertThat(result.orderCount()).isEqualTo(1);
    assertThat(result.inventoryMovementCount()).isZero();
    assertThat(string("select allocation_status from orders where import_batch_id = ?", batch))
        .isEqualTo("UNALLOCATED");
    assertThat(
            integer(
                "select count(*) from order_items where order_id = (select id from orders where import_batch_id = ?)",
                batch))
        .isZero();

    UUID secondBatch = UUID.randomUUID();
    insertBatch(tenant, actor, secondBatch, "TRANSACTION_HISTORY");
    jdbc.update(
        """
            insert into import_rows
              (id, tenant_id, import_batch_id, row_number, row_type, processing_status,
               external_transaction_id, fingerprint, normalized_data, sanitized_raw_data,
               validation_errors, created_at)
            values (?, ?, ?, 1, 'TRANSACTION_HISTORY', 'VALID', 'tx-financial-1', ?,
                    cast(? as jsonb), '{}'::jsonb, '[]'::jsonb, now())
            """,
        UUID.randomUUID(),
        tenant,
        secondBatch,
        "c".repeat(64),
        """
                {"transactionId":"tx-financial-1","status":"SUCCESSFUL","type":"SALE",
                 "occurredAt":"2026-07-02T12:00:00Z","currency":"EUR","amount":20.95}
                """);
    SumUpImportCommitter.Result replay =
        committer.confirm(new SumUpImportCommitter.ConfirmCommand(tenant, actor, secondBatch, 1));
    assertThat(replay.updatedRows()).isEqualTo(1);
    assertThat(replay.orderCount()).isZero();
    assertThat(integer("select count(*) from external_transactions where tenant_id = ?", tenant))
        .isEqualTo(1);
    assertThat(integer("select count(*) from orders where tenant_id = ?", tenant)).isEqualTo(1);

    UUID refundBatch = UUID.randomUUID();
    insertBatch(tenant, actor, refundBatch, "TRANSACTION_HISTORY");
    insertTransactionRow(
        tenant, refundBatch, "tx-financial-1", "d".repeat(64), "REFUNDED", "PAYMENT", "19.95");
    committer.confirm(new SumUpImportCommitter.ConfirmCommand(tenant, actor, refundBatch, 1));
    assertThat(string("select status from orders where tenant_id = ?", tenant))
        .isEqualTo("REFUNDED");
    assertThat(decimal("select refund_amount from orders where tenant_id = ?", tenant))
        .isEqualByComparingTo("19.9500");
    assertThat(string("select status from payments where tenant_id = ?", tenant))
        .isEqualTo("REFUNDED");
    assertThat(integer("select count(*) from order_refunds where tenant_id = ?", tenant))
        .isEqualTo(1);
    assertThat(integer("select count(*) from inventory_movements where tenant_id = ?", tenant))
        .isZero();

    UUID repeatedRefund = UUID.randomUUID();
    insertBatch(tenant, actor, repeatedRefund, "TRANSACTION_HISTORY");
    insertTransactionRow(
        tenant, repeatedRefund, "tx-financial-1", "e".repeat(64), "REFUNDED", "PAYMENT", "19.95");
    committer.confirm(new SumUpImportCommitter.ConfirmCommand(tenant, actor, repeatedRefund, 1));
    assertThat(integer("select count(*) from order_refunds where tenant_id = ?", tenant))
        .isEqualTo(1);
    assertThat(integer("select count(*) from inventory_movements where tenant_id = ?", tenant))
        .isZero();

    Map<String, Object> reportTotals =
        jdbc.queryForMap(
            """
            select count(*) as included_count, sum(gross) as gross, sum(refund) as refund, sum(net) as net
              from (
                select o.total_amount + o.discount_amount as gross, o.refund_amount as refund,
                       o.total_amount - o.refund_amount as net
                  from orders o where o.tenant_id = ?
                    and o.status in ('CONFIRMED','COMPLETED','PARTIALLY_REFUNDED','REFUNDED')
                union all
                select e.amount, coalesce(e.refund_amount, 0), e.amount - coalesce(e.refund_amount, 0)
                  from external_transactions e
                 where e.tenant_id = ? and e.active = true and e.provider = 'SUMUP'
                   and e.linked_order_id is null
                   and not exists (select 1 from orders o where o.tenant_id = e.tenant_id
                                   and o.external_provider = e.provider
                                   and o.external_transaction_id = e.provider_transaction_id)
              ) included
            """,
            tenant,
            tenant);
    assertThat(((Number) reportTotals.get("included_count")).longValue()).isEqualTo(1);
    assertThat((BigDecimal) reportTotals.get("gross")).isEqualByComparingTo("19.9500");
    assertThat((BigDecimal) reportTotals.get("refund")).isEqualByComparingTo("19.9500");
    assertThat((BigDecimal) reportTotals.get("net")).isEqualByComparingTo("0.0000");
  }

  @Test
  void mappedOrderRefundUpdatesSalesRecordsWithoutChangingInventory() {
    UUID tenant = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    UUID product = UUID.randomUUID();
    insertTenantAndActor(tenant, actor);
    jdbc.update(
        """
            insert into products
              (id, tenant_id, sku, name, sale_price, currency, current_stock, low_stock_threshold,
               enabled, version, created_at, updated_at)
            values (?, ?, 'REFUND-ART', 'Refundable print', 25.0000, 'EUR', 5, 1, true, 0, now(), now())
            """,
        product,
        tenant);

    UUID saleBatch = UUID.randomUUID();
    insertBatch(tenant, actor, saleBatch, "ORDER_HISTORY");
    insertOrderRow(
        tenant,
        saleBatch,
        product,
        "tx-refund-order",
        "sale-order",
        "f".repeat(64),
        "SUCCESSFUL",
        "SALE",
        2,
        null);
    committer.confirm(new SumUpImportCommitter.ConfirmCommand(tenant, actor, saleBatch, 1));
    assertThat(integer("select current_stock from products where id = ?", product)).isEqualTo(5);

    UUID partialBatch = UUID.randomUUID();
    insertBatch(tenant, actor, partialBatch, "ORDER_HISTORY");
    insertOrderRow(
        tenant,
        partialBatch,
        product,
        "tx-refund-order",
        "sale-order",
        "1".repeat(64),
        "PARTIALLY_REFUNDED",
        "REFUND",
        1,
        "25.00");
    SumUpImportCommitter.Result partial =
        committer.confirm(new SumUpImportCommitter.ConfirmCommand(tenant, actor, partialBatch, 1));
    assertThat(partial.inventoryMovementCount()).isZero();
    assertThat(integer("select current_stock from products where id = ?", product)).isEqualTo(5);
    assertThat(string("select status from orders where tenant_id = ?", tenant))
        .isEqualTo("PARTIALLY_REFUNDED");
    assertThat(decimal("select refund_amount from orders where tenant_id = ?", tenant))
        .isEqualByComparingTo("25.0000");
    assertThat(integer("select refunded_quantity from order_items where tenant_id = ?", tenant))
        .isEqualTo(1);

    UUID repeatedPartial = UUID.randomUUID();
    insertBatch(tenant, actor, repeatedPartial, "ORDER_HISTORY");
    insertOrderRow(
        tenant,
        repeatedPartial,
        product,
        "tx-refund-order",
        "sale-order",
        "2".repeat(64),
        "PARTIALLY_REFUNDED",
        "REFUND",
        1,
        "25.00");
    SumUpImportCommitter.Result replay =
        committer.confirm(
            new SumUpImportCommitter.ConfirmCommand(tenant, actor, repeatedPartial, 1));
    assertThat(replay.inventoryMovementCount()).isZero();
    assertThat(integer("select current_stock from products where id = ?", product)).isEqualTo(5);
    assertThat(integer("select count(*) from order_refunds where tenant_id = ?", tenant))
        .isEqualTo(1);
    assertThat(integer("select refunded_quantity from order_items where tenant_id = ?", tenant))
        .isEqualTo(1);

    UUID fullBatch = UUID.randomUUID();
    insertBatch(tenant, actor, fullBatch, "ORDER_HISTORY");
    insertOrderRow(
        tenant,
        fullBatch,
        product,
        "tx-refund-order",
        "sale-order",
        "3".repeat(64),
        "REFUNDED",
        "REFUND",
        2,
        "50.00");
    committer.confirm(new SumUpImportCommitter.ConfirmCommand(tenant, actor, fullBatch, 1));
    assertThat(integer("select current_stock from products where id = ?", product)).isEqualTo(5);
    assertThat(string("select status from orders where tenant_id = ?", tenant))
        .isEqualTo("REFUNDED");
    assertThat(integer("select refunded_quantity from order_items where tenant_id = ?", tenant))
        .isEqualTo(2);
    assertThat(integer("select count(*) from order_refunds where tenant_id = ?", tenant))
        .isEqualTo(2);
    assertThat(
            integer(
                "select count(*) from inventory_movements where tenant_id = ? and movement_type = 'ORDER_REFUND'",
                tenant))
        .isZero();
    assertThat(string("select status from payments where tenant_id = ?", tenant))
        .isEqualTo("REFUNDED");
  }

  private void insertTransactionRow(
      UUID tenant,
      UUID batch,
      String transactionId,
      String fingerprint,
      String status,
      String type,
      String refundAmount) {
    String normalized =
        """
            {"transactionId":"%s","status":"%s","type":"%s",
             "occurredAt":"2026-07-02T12:00:00Z","currency":"EUR","amount":19.95,
             "refundAmount":%s}
            """
            .formatted(transactionId, status, type, refundAmount);
    jdbc.update(
        """
            insert into import_rows
              (id, tenant_id, import_batch_id, row_number, row_type, processing_status,
               external_transaction_id, fingerprint, normalized_data, sanitized_raw_data,
               validation_errors, created_at)
            values (?, ?, ?, 1, 'TRANSACTION_HISTORY', 'VALID', ?, ?,
                    cast(? as jsonb), '{}'::jsonb, '[]'::jsonb, now())
            """,
        UUID.randomUUID(),
        tenant,
        batch,
        transactionId,
        fingerprint,
        normalized);
  }

  private void insertOrderRow(
      UUID tenant,
      UUID batch,
      UUID product,
      String transactionId,
      String transactionCode,
      String fingerprint,
      String status,
      String type,
      int quantity,
      String refundAmount) {
    String refundField = refundAmount == null ? "" : ",\"refundAmount\":" + refundAmount;
    String normalized =
        """
            {"transactionId":"%s","transactionCode":"%s","status":"%s","type":"%s",
             "occurredAt":"2026-07-01T10:00:00Z","currency":"EUR","productName":"Refundable print",
             "sku":"REFUND-ART","quantity":%d,"unitPrice":25,"revenue":50%s}
            """
            .formatted(transactionId, transactionCode, status, type, quantity, refundField);
    jdbc.update(
        """
            insert into import_rows
              (id, tenant_id, import_batch_id, row_number, row_type, processing_status,
               external_transaction_id, fingerprint, normalized_data, sanitized_raw_data,
               validation_errors, linked_product_id, created_at)
            values (?, ?, ?, 1, 'ORDER_HISTORY', 'VALID', ?, ?,
                    cast(? as jsonb), '{}'::jsonb, '[]'::jsonb, ?, now())
            """,
        UUID.randomUUID(),
        tenant,
        batch,
        transactionId,
        fingerprint,
        normalized,
        product);
  }

  private void insertCurrencyMismatchRow(
      UUID tenant, UUID batch, UUID product, String rowType, String transactionId) {
    String normalized =
        transactionId == null
            ? """
                {"occurredAt":"2026-07-03T10:00:00Z","currency":"USD",
                 "productName":"Euro print","sku":"EUR-ART","quantity":2,"grossRevenue":50}
                """
            : """
                {"transactionId":"%s","transactionCode":"%s","status":"SUCCESSFUL","type":"SALE",
                 "occurredAt":"2026-07-03T10:00:00Z","currency":"USD",
                 "productName":"Euro print","sku":"EUR-ART","quantity":2,"unitPrice":25,"revenue":50}
                """
                .formatted(transactionId, transactionId);
    jdbc.update(
        """
            insert into import_rows
              (id, tenant_id, import_batch_id, row_number, row_type, processing_status,
               external_transaction_id, fingerprint, normalized_data, sanitized_raw_data,
               validation_errors, linked_product_id, created_at)
            values (?, ?, ?, 1, ?, 'VALID', ?, ?, cast(? as jsonb), '{}'::jsonb,
                    '[]'::jsonb, ?, now())
            """,
        UUID.randomUUID(),
        tenant,
        batch,
        rowType,
        transactionId,
        UUID.randomUUID().toString().replace("-", "").repeat(2),
        normalized,
        product);
  }

  private void insertTenantAndActor(UUID tenant, UUID actor) {
    jdbc.update(
        """
            insert into tenants
              (id, name, slug, default_currency, timezone, locale, enabled, created_at, updated_at)
            values (?, 'Test tenant', ?, 'EUR', 'Europe/Paris', 'en', true, now(), now())
            """,
        tenant,
        "tenant-" + tenant);
    jdbc.update(
        """
            insert into users
              (id, tenant_id, username, email, password_hash, display_name, role,
               preferred_locale, enabled, created_at, updated_at)
            values (?, ?, ?, ?, 'not-used-by-this-test', 'Importer', 'USER', 'en', true, now(), now())
            """,
        actor,
        tenant,
        "user-" + actor,
        actor + "@example.test");
  }

  private void insertBatch(UUID tenant, UUID actor, UUID batch, String type) {
    UUID event = UUID.randomUUID();
    String eventName = "Test exhibition " + batch.toString().substring(0, 8);
    jdbc.update(
        """
            insert into sales_events
              (id, tenant_id, name, start_date, end_date, enabled, created_at, updated_at)
            values (?, ?, ?, '2026-07-01', '2026-07-05', true, now(), now())
            """,
        event,
        tenant,
        eventName);
    jdbc.update(
        """
            insert into import_batches
              (id, tenant_id, source_provider, import_type, original_filename, stored_object_key,
               file_checksum, file_size, analysis_version, status, event_id, event_name, created_by, created_at)
            values (?, ?, 'SUMUP', ?, 'test.csv', ?, ?, 100, 1, 'READY_FOR_CONFIRMATION', ?, ?, ?, now())
            """,
        batch,
        tenant,
        type,
        "tenants/" + tenant + "/imports/" + batch,
        batch.toString().replace("-", "").repeat(2),
        event,
        eventName,
        actor);
  }

  private int integer(String sql, Object... arguments) {
    Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
    return value == null ? 0 : value;
  }

  private String string(String sql, Object... arguments) {
    return jdbc.queryForObject(sql, String.class, arguments);
  }

  private BigDecimal decimal(String sql, Object... arguments) {
    return jdbc.queryForObject(sql, BigDecimal.class, arguments);
  }
}
