CREATE INDEX idx_orders_tenant_channel_date
    ON orders(tenant_id, sales_channel, order_date DESC);

CREATE INDEX idx_orders_tenant_creator_date
    ON orders(tenant_id, created_by, order_date DESC);

CREATE INDEX idx_orders_date_global
    ON orders(order_date DESC);

CREATE INDEX idx_inventory_tenant_operator_date
    ON inventory_movements(tenant_id, operator_id, created_at DESC);

CREATE INDEX idx_inventory_created_global
    ON inventory_movements(created_at DESC);
