CREATE TABLE inventory_sale_batches (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    sales_channel VARCHAR(24) NOT NULL,
    event_id UUID,
    event_name VARCHAR(240),
    currency VARCHAR(3) NOT NULL,
    attributed_date DATE NOT NULL,
    remark TEXT,
    operator_id UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_inventory_sale_event
        FOREIGN KEY (tenant_id, event_id)
        REFERENCES sales_events(tenant_id, id),
    UNIQUE (tenant_id, id)
);

CREATE INDEX idx_inventory_sale_batches_tenant_date
    ON inventory_sale_batches(tenant_id, attributed_date DESC);

CREATE INDEX idx_inventory_sale_batches_tenant_channel_date
    ON inventory_sale_batches(tenant_id, sales_channel, attributed_date DESC);

CREATE INDEX idx_inventory_sale_batches_tenant_event_date
    ON inventory_sale_batches(tenant_id, event_id, attributed_date DESC);

ALTER TABLE inventory_movements
    ADD COLUMN sale_batch_id UUID,
    ADD COLUMN unit_price NUMERIC(19,4),
    ADD CONSTRAINT chk_inventory_movement_unit_price
        CHECK (unit_price IS NULL OR unit_price >= 0),
    ADD CONSTRAINT fk_inventory_movement_sale_batch
        FOREIGN KEY (tenant_id, sale_batch_id)
        REFERENCES inventory_sale_batches(tenant_id, id);

CREATE INDEX idx_inventory_movements_sale_batch
    ON inventory_movements(tenant_id, sale_batch_id);
