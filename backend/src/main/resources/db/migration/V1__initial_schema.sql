CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    slug VARCHAR(100) NOT NULL UNIQUE,
    default_currency VARCHAR(3) NOT NULL DEFAULT 'EUR',
    timezone VARCHAR(80) NOT NULL DEFAULT 'Europe/Paris',
    locale VARCHAR(10) NOT NULL DEFAULT 'zh-CN',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE users (
    id UUID PRIMARY KEY,
    tenant_id UUID REFERENCES tenants(id),
    username VARCHAR(80) NOT NULL UNIQUE,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(120) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('USER','ADMIN')),
    preferred_locale VARCHAR(10) NOT NULL DEFAULT 'en' CHECK (preferred_locale IN ('en','zh-CN','fr-FR')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT user_tenant_role CHECK ((role = 'ADMIN') OR tenant_id IS NOT NULL),
    UNIQUE (tenant_id, id)
);

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    family_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by UUID,
    created_at TIMESTAMPTZ NOT NULL,
    created_ip VARCHAR(64),
    user_agent VARCHAR(500)
);
CREATE INDEX idx_refresh_user_family ON refresh_tokens(user_id, family_id);

CREATE TABLE products (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    sku VARCHAR(100) NOT NULL,
    name VARCHAR(240) NOT NULL,
    category VARCHAR(160),
    artist_name VARCHAR(160),
    description TEXT,
    image_object_key VARCHAR(700),
    cost_price NUMERIC(19,4),
    sale_price NUMERIC(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    current_stock INTEGER NOT NULL DEFAULT 0 CHECK (current_stock >= 0),
    low_stock_threshold INTEGER NOT NULL DEFAULT 0 CHECK (low_stock_threshold >= 0),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, sku),
    UNIQUE (tenant_id, id)
);
CREATE INDEX idx_products_tenant_search ON products(tenant_id, enabled, name);

CREATE TABLE orders (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    order_number VARCHAR(80) NOT NULL,
    source VARCHAR(24) NOT NULL,
    external_provider VARCHAR(24),
    external_transaction_id VARCHAR(200),
    status VARCHAR(32) NOT NULL,
    allocation_status VARCHAR(32) NOT NULL,
    sales_channel VARCHAR(24) NOT NULL,
    event_name VARCHAR(240),
    customer_name VARCHAR(240),
    customer_email VARCHAR(254),
    customer_note TEXT,
    currency VARCHAR(3) NOT NULL,
    subtotal NUMERIC(19,4) NOT NULL,
    discount_amount NUMERIC(19,4) NOT NULL,
    tax_amount NUMERIC(19,4) NOT NULL,
    refund_amount NUMERIC(19,4) NOT NULL,
    total_amount NUMERIC(19,4) NOT NULL,
    unallocated_amount NUMERIC(19,4) NOT NULL,
    payment_method VARCHAR(32) NOT NULL,
    payment_status VARCHAR(32) NOT NULL,
    order_date TIMESTAMPTZ NOT NULL,
    inventory_applied BOOLEAN NOT NULL DEFAULT FALSE,
    manually_modified_after_import BOOLEAN NOT NULL DEFAULT FALSE,
    import_batch_id UUID,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, order_number),
    UNIQUE (tenant_id, id)
);
CREATE INDEX idx_orders_tenant_date ON orders(tenant_id, order_date DESC);
CREATE INDEX idx_orders_tenant_status ON orders(tenant_id, status, allocation_status);

CREATE TABLE order_items (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    order_id UUID NOT NULL,
    product_id UUID,
    product_sku_snapshot VARCHAR(100),
    product_name_snapshot VARCHAR(240) NOT NULL,
    unit_price NUMERIC(19,4) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    discount_amount NUMERIC(19,4) NOT NULL,
    tax_rate NUMERIC(9,4) NOT NULL,
    tax_amount NUMERIC(19,4) NOT NULL,
    line_total NUMERIC(19,4) NOT NULL,
    refunded_quantity INTEGER NOT NULL DEFAULT 0 CHECK (refunded_quantity >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_item_order FOREIGN KEY (tenant_id, order_id) REFERENCES orders(tenant_id, id),
    CONSTRAINT fk_item_product FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id),
    UNIQUE (tenant_id, id)
);
CREATE INDEX idx_order_items_order ON order_items(tenant_id, order_id);

CREATE TABLE inventory_movements (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    product_id UUID NOT NULL,
    movement_type VARCHAR(32) NOT NULL,
    quantity INTEGER NOT NULL,
    stock_before INTEGER NOT NULL,
    stock_after INTEGER NOT NULL CHECK (stock_after >= 0),
    related_order_id UUID,
    related_import_batch_id UUID,
    reference VARCHAR(240),
    remark TEXT,
    operator_id UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_movement_product FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id)
);
CREATE INDEX idx_inventory_tenant_product_date ON inventory_movements(tenant_id, product_id, created_at DESC);

CREATE TABLE payments (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    order_id UUID NOT NULL,
    provider VARCHAR(24),
    provider_transaction_id VARCHAR(200),
    amount NUMERIC(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    payment_method VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    paid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_payment_order FOREIGN KEY (tenant_id, order_id) REFERENCES orders(tenant_id, id)
);

CREATE TABLE order_refunds (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    order_id UUID NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    reason TEXT,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_refund_order FOREIGN KEY (tenant_id, order_id) REFERENCES orders(tenant_id, id),
    UNIQUE (tenant_id, id)
);
CREATE TABLE order_refund_items (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    refund_id UUID NOT NULL,
    order_item_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    amount NUMERIC(19,4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_refund_item_refund FOREIGN KEY (tenant_id, refund_id) REFERENCES order_refunds(tenant_id, id),
    CONSTRAINT fk_refund_item_order_item FOREIGN KEY (tenant_id, order_item_id) REFERENCES order_items(tenant_id, id)
);

CREATE TABLE import_batches (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    source_provider VARCHAR(24) NOT NULL,
    import_type VARCHAR(40) NOT NULL,
    original_filename VARCHAR(500) NOT NULL,
    stored_object_key VARCHAR(700) NOT NULL,
    file_checksum VARCHAR(64) NOT NULL,
    file_size BIGINT NOT NULL,
    detected_encoding VARCHAR(40),
    detected_delimiter VARCHAR(20),
    analysis_version INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(40) NOT NULL,
    total_rows INTEGER NOT NULL DEFAULT 0,
    valid_rows INTEGER NOT NULL DEFAULT 0,
    imported_rows INTEGER NOT NULL DEFAULT 0,
    updated_rows INTEGER NOT NULL DEFAULT 0,
    duplicate_rows INTEGER NOT NULL DEFAULT 0,
    skipped_rows INTEGER NOT NULL DEFAULT 0,
    error_rows INTEGER NOT NULL DEFAULT 0,
    inventory_movement_count INTEGER NOT NULL DEFAULT 0,
    order_count INTEGER NOT NULL DEFAULT 0,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    reversed_at TIMESTAMPTZ,
    reversed_by UUID REFERENCES users(id),
    UNIQUE (tenant_id, source_provider, file_checksum),
    UNIQUE (tenant_id, id)
);
CREATE INDEX idx_import_batches_tenant_status ON import_batches(tenant_id, status, created_at DESC);

ALTER TABLE orders ADD CONSTRAINT fk_order_import_batch FOREIGN KEY (tenant_id, import_batch_id) REFERENCES import_batches(tenant_id, id);
ALTER TABLE inventory_movements ADD CONSTRAINT fk_movement_import_batch FOREIGN KEY (tenant_id, related_import_batch_id) REFERENCES import_batches(tenant_id, id);

CREATE TABLE external_transactions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    provider VARCHAR(24) NOT NULL,
    provider_transaction_id VARCHAR(200),
    provider_transaction_code VARCHAR(200),
    provider_merchant_code VARCHAR(200),
    transaction_type VARCHAR(32) NOT NULL,
    transaction_status VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    fee_amount NUMERIC(19,4),
    net_amount NUMERIC(19,4),
    refund_amount NUMERIC(19,4),
    payment_method VARCHAR(32),
    card_type VARCHAR(80),
    masked_card_info VARCHAR(80),
    payout_reference VARCHAR(240),
    payout_date TIMESTAMPTZ,
    description TEXT,
    linked_order_id UUID,
    import_batch_id UUID NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    raw_data JSONB NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_external_order FOREIGN KEY (tenant_id, linked_order_id) REFERENCES orders(tenant_id, id),
    CONSTRAINT fk_external_batch FOREIGN KEY (tenant_id, import_batch_id) REFERENCES import_batches(tenant_id, id),
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX uq_external_provider_id ON external_transactions(tenant_id, provider, provider_transaction_id) WHERE provider_transaction_id IS NOT NULL;
CREATE UNIQUE INDEX uq_external_fingerprint ON external_transactions(tenant_id, provider, fingerprint);
CREATE INDEX idx_external_tenant_date ON external_transactions(tenant_id, occurred_at DESC);

CREATE TABLE external_product_mappings (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    provider VARCHAR(24) NOT NULL,
    external_product_reference VARCHAR(240),
    external_product_name VARCHAR(500),
    normalized_external_name VARCHAR(500) NOT NULL,
    product_id UUID NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_mapping_product FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id),
    UNIQUE (tenant_id, provider, normalized_external_name)
);

CREATE TABLE import_rows (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    import_batch_id UUID NOT NULL,
    row_number INTEGER NOT NULL,
    row_type VARCHAR(40) NOT NULL,
    processing_status VARCHAR(24) NOT NULL,
    external_transaction_id VARCHAR(200),
    fingerprint VARCHAR(64),
    normalized_data JSONB NOT NULL,
    sanitized_raw_data JSONB NOT NULL,
    validation_errors JSONB NOT NULL,
    linked_order_id UUID,
    linked_product_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_row_batch FOREIGN KEY (tenant_id, import_batch_id) REFERENCES import_batches(tenant_id, id),
    CONSTRAINT fk_row_order FOREIGN KEY (tenant_id, linked_order_id) REFERENCES orders(tenant_id, id),
    CONSTRAINT fk_row_product FOREIGN KEY (tenant_id, linked_product_id) REFERENCES products(tenant_id, id),
    UNIQUE (tenant_id, import_batch_id, row_number)
);
CREATE INDEX idx_import_rows_batch_status ON import_rows(tenant_id, import_batch_id, processing_status, row_number);

CREATE TABLE import_column_mappings (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    import_batch_id UUID NOT NULL,
    source_column VARCHAR(300) NOT NULL,
    target_field VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_column_batch FOREIGN KEY (tenant_id, import_batch_id) REFERENCES import_batches(tenant_id, id),
    UNIQUE (tenant_id, import_batch_id, source_column)
);

CREATE TABLE imported_sales_summaries (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    import_batch_id UUID NOT NULL,
    product_id UUID,
    external_product_name VARCHAR(500),
    period_start TIMESTAMPTZ,
    period_end TIMESTAMPTZ,
    quantity INTEGER,
    gross_amount NUMERIC(19,4),
    net_amount NUMERIC(19,4),
    currency VARCHAR(3) NOT NULL,
    inventory_applied BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_sales_summary_batch FOREIGN KEY (tenant_id, import_batch_id) REFERENCES import_batches(tenant_id, id),
    CONSTRAINT fk_sales_summary_product FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id)
);

CREATE TABLE imported_accounting_summaries (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    import_batch_id UUID NOT NULL,
    summary_date DATE,
    payment_method VARCHAR(80),
    tax_rate NUMERIC(9,4),
    gross_amount NUMERIC(19,4),
    tax_amount NUMERIC(19,4),
    fee_amount NUMERIC(19,4),
    net_amount NUMERIC(19,4),
    currency VARCHAR(3) NOT NULL,
    raw_data JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_accounting_batch FOREIGN KEY (tenant_id, import_batch_id) REFERENCES import_batches(tenant_id, id)
);

CREATE TABLE stored_files (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    object_key VARCHAR(700) NOT NULL UNIQUE,
    original_filename VARCHAR(500),
    content_type VARCHAR(160) NOT NULL,
    size BIGINT,
    checksum VARCHAR(64),
    purpose VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL,
    resource_type VARCHAR(40),
    resource_id UUID,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ
);
CREATE INDEX idx_stored_files_tenant_resource ON stored_files(tenant_id, resource_type, resource_id);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    tenant_id UUID REFERENCES tenants(id),
    actor_user_id UUID REFERENCES users(id),
    actor_role VARCHAR(16),
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(80),
    resource_id UUID,
    result VARCHAR(24) NOT NULL,
    ip_address VARCHAR(64),
    user_agent VARCHAR(500),
    metadata JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_audit_tenant_date ON audit_logs(tenant_id, created_at DESC);
CREATE INDEX idx_audit_actor_date ON audit_logs(actor_user_id, created_at DESC);
