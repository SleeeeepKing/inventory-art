DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM orders WHERE source <> 'SUMUP_IMPORT' AND event_id IS NULL) THEN
        RAISE EXCEPTION 'Manual orders without a sales event must be removed or assigned before V8';
    END IF;
    IF EXISTS (SELECT 1 FROM inventory_sale_batches WHERE event_id IS NULL) THEN
        RAISE EXCEPTION 'Inventory sale batches without a sales event must be removed or assigned before V8';
    END IF;
END $$;

DELETE FROM orders WHERE source = 'SUMUP_IMPORT';
DELETE FROM stored_files WHERE purpose <> 'PRODUCT_IMAGE';

DROP TABLE order_refund_items;
DROP TABLE order_refunds;
DROP TABLE payments;
DROP TABLE order_items;

DROP TABLE import_column_mappings;
DROP TABLE imported_sales_summaries;
DROP TABLE imported_accounting_summaries;
DROP TABLE import_rows;
DROP TABLE external_product_mappings;
DROP TABLE external_transactions;

ALTER TABLE inventory_movements
    DROP CONSTRAINT fk_movement_import_batch,
    DROP CONSTRAINT fk_movement_order,
    DROP COLUMN related_order_id,
    DROP COLUMN related_import_batch_id,
    DROP COLUMN unit_price;

ALTER TABLE orders DROP CONSTRAINT fk_order_import_batch;
ALTER TABLE import_batches DROP CONSTRAINT fk_import_batch_sales_event;
DROP TABLE import_batches;

DROP INDEX idx_orders_tenant_status;
DROP INDEX idx_orders_tenant_channel_date;

ALTER TABLE orders
    DROP COLUMN source,
    DROP COLUMN external_provider,
    DROP COLUMN external_transaction_id,
    DROP COLUMN status,
    DROP COLUMN allocation_status,
    DROP COLUMN sales_channel,
    DROP COLUMN event_name,
    DROP COLUMN customer_note,
    DROP COLUMN subtotal,
    DROP COLUMN discount_amount,
    DROP COLUMN tax_amount,
    DROP COLUMN refund_amount,
    DROP COLUMN unallocated_amount,
    DROP COLUMN payment_method,
    DROP COLUMN payment_status,
    DROP COLUMN inventory_applied,
    DROP COLUMN manually_modified_after_import,
    DROP COLUMN import_batch_id,
    ALTER COLUMN event_id SET NOT NULL,
    ADD CONSTRAINT chk_orders_total_positive CHECK (total_amount > 0);

DROP INDEX idx_inventory_sale_batches_tenant_channel_date;

ALTER TABLE inventory_sale_batches
    DROP COLUMN sales_channel,
    DROP COLUMN event_name,
    DROP COLUMN currency,
    DROP COLUMN remark,
    ALTER COLUMN event_id SET NOT NULL;

ALTER TABLE stored_files
    DROP COLUMN purpose,
    DROP COLUMN resource_type;

ALTER TABLE stored_files RENAME COLUMN resource_id TO product_id;

ALTER TABLE stored_files
    ALTER COLUMN product_id SET NOT NULL,
    ADD CONSTRAINT fk_stored_file_product
        FOREIGN KEY (tenant_id, product_id)
        REFERENCES products(tenant_id, id);
