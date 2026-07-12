CREATE TABLE sales_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(240) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, name),
    UNIQUE (tenant_id, id)
);

CREATE INDEX idx_sales_events_tenant_enabled_name
    ON sales_events(tenant_id, enabled, name);

ALTER TABLE orders
    ADD COLUMN event_id UUID;

ALTER TABLE orders
    ADD CONSTRAINT fk_orders_sales_event
    FOREIGN KEY (tenant_id, event_id)
    REFERENCES sales_events(tenant_id, id);

CREATE INDEX idx_orders_tenant_event ON orders(tenant_id, event_id);
