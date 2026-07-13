CREATE TABLE product_families (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(240) NOT NULL,
    category VARCHAR(160),
    artist_name VARCHAR(160),
    description TEXT,
    image_object_key VARCHAR(700),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, id)
);

CREATE INDEX idx_product_families_tenant_updated
    ON product_families(tenant_id, updated_at DESC);

ALTER TABLE products
    ADD COLUMN family_id UUID,
    ADD COLUMN variant_name VARCHAR(160);

INSERT INTO product_families(
    id, tenant_id, name, category, artist_name, description, image_object_key,
    version, created_at, updated_at
)
SELECT id, tenant_id, name, category, artist_name, description, image_object_key,
       0, created_at, updated_at
FROM products;

UPDATE products SET family_id = id WHERE family_id IS NULL;

ALTER TABLE products
    ADD CONSTRAINT fk_product_family
        FOREIGN KEY (tenant_id, family_id)
        REFERENCES product_families(tenant_id, id);

CREATE INDEX idx_products_tenant_family
    ON products(tenant_id, family_id, updated_at DESC);

ALTER TABLE stored_files ADD COLUMN product_family_id UUID;
ALTER TABLE stored_files ALTER COLUMN product_id DROP NOT NULL;

UPDATE stored_files f
SET product_family_id = p.family_id
FROM products p
WHERE p.tenant_id = f.tenant_id
  AND p.id = f.product_id
  AND f.product_family_id IS NULL;

ALTER TABLE stored_files
    ADD CONSTRAINT fk_stored_file_product_family
        FOREIGN KEY (tenant_id, product_family_id)
        REFERENCES product_families(tenant_id, id);

CREATE INDEX idx_stored_files_tenant_family
    ON stored_files(tenant_id, product_family_id, created_at DESC);

ALTER TABLE products ALTER COLUMN current_stock SET DEFAULT 999;
ALTER TABLE products ALTER COLUMN low_stock_threshold SET DEFAULT 5;
