ALTER TABLE orders
    DROP COLUMN customer_name,
    DROP COLUMN customer_email;

ALTER TABLE order_refund_items
    DROP CONSTRAINT fk_refund_item_refund,
    DROP CONSTRAINT fk_refund_item_order_item;

ALTER TABLE order_refund_items
    ADD CONSTRAINT fk_refund_item_refund
        FOREIGN KEY (tenant_id, refund_id)
        REFERENCES order_refunds(tenant_id, id)
        ON DELETE CASCADE,
    ADD CONSTRAINT fk_refund_item_order_item
        FOREIGN KEY (tenant_id, order_item_id)
        REFERENCES order_items(tenant_id, id)
        ON DELETE CASCADE;

ALTER TABLE order_items
    DROP CONSTRAINT fk_item_order,
    ADD CONSTRAINT fk_item_order
        FOREIGN KEY (tenant_id, order_id)
        REFERENCES orders(tenant_id, id)
        ON DELETE CASCADE;

ALTER TABLE payments
    DROP CONSTRAINT fk_payment_order,
    ADD CONSTRAINT fk_payment_order
        FOREIGN KEY (tenant_id, order_id)
        REFERENCES orders(tenant_id, id)
        ON DELETE CASCADE;

ALTER TABLE order_refunds
    DROP CONSTRAINT fk_refund_order,
    ADD CONSTRAINT fk_refund_order
        FOREIGN KEY (tenant_id, order_id)
        REFERENCES orders(tenant_id, id)
        ON DELETE CASCADE;

ALTER TABLE external_transactions
    DROP CONSTRAINT fk_external_order,
    ADD CONSTRAINT fk_external_order
        FOREIGN KEY (tenant_id, linked_order_id)
        REFERENCES orders(tenant_id, id)
        ON DELETE SET NULL (linked_order_id);

ALTER TABLE import_rows
    DROP CONSTRAINT fk_row_order,
    ADD CONSTRAINT fk_row_order
        FOREIGN KEY (tenant_id, linked_order_id)
        REFERENCES orders(tenant_id, id)
        ON DELETE SET NULL (linked_order_id);

ALTER TABLE inventory_movements
    DROP CONSTRAINT fk_movement_order,
    ADD CONSTRAINT fk_movement_order
        FOREIGN KEY (tenant_id, related_order_id)
        REFERENCES orders(tenant_id, id)
        ON DELETE SET NULL (related_order_id);
