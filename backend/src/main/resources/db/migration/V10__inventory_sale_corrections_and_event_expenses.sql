ALTER TABLE inventory_sale_batches
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN updated_by UUID,
    ADD COLUMN updated_at TIMESTAMPTZ,
    ADD COLUMN cancelled_by UUID,
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_inventory_sale_batch_status CHECK (status IN ('ACTIVE', 'CANCELLED')),
    ADD CONSTRAINT fk_inventory_sale_batch_updated_by
        FOREIGN KEY (tenant_id, updated_by) REFERENCES users(tenant_id, id),
    ADD CONSTRAINT fk_inventory_sale_batch_cancelled_by
        FOREIGN KEY (tenant_id, cancelled_by) REFERENCES users(tenant_id, id);

UPDATE inventory_sale_batches
SET updated_by = operator_id,
    updated_at = created_at;

ALTER TABLE inventory_sale_batches
    ALTER COLUMN updated_at SET NOT NULL;

CREATE TABLE inventory_sale_lines (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    sale_batch_id UUID NOT NULL,
    product_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_inventory_sale_line_batch
        FOREIGN KEY (tenant_id, sale_batch_id)
        REFERENCES inventory_sale_batches(tenant_id, id),
    CONSTRAINT fk_inventory_sale_line_product
        FOREIGN KEY (tenant_id, product_id)
        REFERENCES products(tenant_id, id),
    UNIQUE (tenant_id, sale_batch_id, product_id),
    UNIQUE (tenant_id, id)
);

INSERT INTO inventory_sale_lines(
    id, tenant_id, sale_batch_id, product_id, quantity, created_at, updated_at
)
SELECT gen_random_uuid(), m.tenant_id, m.sale_batch_id, m.product_id,
       sum(abs(m.quantity))::integer, min(m.created_at), max(m.created_at)
FROM inventory_movements m
WHERE m.sale_batch_id IS NOT NULL
  AND m.movement_type = 'SALE'
  AND m.quantity < 0
GROUP BY m.tenant_id, m.sale_batch_id, m.product_id;

CREATE INDEX idx_inventory_sale_lines_tenant_product
    ON inventory_sale_lines(tenant_id, product_id, sale_batch_id);

CREATE TABLE expense_categories (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(160) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, id)
);

CREATE UNIQUE INDEX uq_expense_categories_tenant_name_ci
    ON expense_categories(tenant_id, lower(name));

CREATE INDEX idx_expense_categories_tenant_enabled_name
    ON expense_categories(tenant_id, enabled, name);

CREATE TABLE sales_event_expenses (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    event_id UUID NOT NULL,
    category_id UUID NOT NULL,
    amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    expense_date DATE NOT NULL,
    note VARCHAR(2000),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_by UUID,
    updated_by UUID,
    voided_by UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    voided_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_sales_event_expense_status CHECK (status IN ('ACTIVE', 'VOIDED')),
    CONSTRAINT fk_sales_event_expense_event
        FOREIGN KEY (tenant_id, event_id)
        REFERENCES sales_events(tenant_id, id),
    CONSTRAINT fk_sales_event_expense_category
        FOREIGN KEY (tenant_id, category_id)
        REFERENCES expense_categories(tenant_id, id),
    CONSTRAINT fk_sales_event_expense_created_by
        FOREIGN KEY (tenant_id, created_by)
        REFERENCES users(tenant_id, id),
    CONSTRAINT fk_sales_event_expense_updated_by
        FOREIGN KEY (tenant_id, updated_by)
        REFERENCES users(tenant_id, id),
    CONSTRAINT fk_sales_event_expense_voided_by
        FOREIGN KEY (tenant_id, voided_by)
        REFERENCES users(tenant_id, id),
    UNIQUE (tenant_id, id)
);

CREATE INDEX idx_sales_event_expenses_tenant_event
    ON sales_event_expenses(tenant_id, event_id, status, expense_date DESC);

CREATE INDEX idx_sales_event_expenses_tenant_category
    ON sales_event_expenses(tenant_id, category_id, status);
