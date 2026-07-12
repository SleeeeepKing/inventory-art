ALTER TABLE sales_events
    ADD COLUMN start_date DATE,
    ADD COLUMN end_date DATE;

UPDATE sales_events event
SET start_date = (event.created_at AT TIME ZONE COALESCE(tenant.timezone, 'UTC'))::date,
    end_date = (event.created_at AT TIME ZONE COALESCE(tenant.timezone, 'UTC'))::date
FROM tenants tenant
WHERE tenant.id = event.tenant_id;

ALTER TABLE sales_events
    ALTER COLUMN start_date SET NOT NULL,
    ALTER COLUMN end_date SET NOT NULL,
    ADD CONSTRAINT chk_sales_event_dates CHECK (end_date >= start_date);

CREATE INDEX idx_sales_events_tenant_end_date
    ON sales_events(tenant_id, end_date DESC);

ALTER TABLE import_batches
    ADD COLUMN event_id UUID,
    ADD COLUMN event_name VARCHAR(240);

ALTER TABLE import_batches
    ADD CONSTRAINT fk_import_batch_sales_event
    FOREIGN KEY (tenant_id, event_id)
    REFERENCES sales_events(tenant_id, id);

CREATE INDEX idx_import_batches_tenant_event
    ON import_batches(tenant_id, event_id, created_at DESC);
