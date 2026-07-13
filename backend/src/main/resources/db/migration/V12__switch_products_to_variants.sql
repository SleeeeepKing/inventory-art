INSERT INTO product_families(
    id, tenant_id, name, category, artist_name, description, image_object_key,
    version, created_at, updated_at
)
SELECT p.id, p.tenant_id, p.name, p.category, p.artist_name, p.description, p.image_object_key,
       0, p.created_at, p.updated_at
FROM products p
WHERE p.family_id IS NULL
ON CONFLICT (id) DO NOTHING;

UPDATE products SET family_id = id WHERE family_id IS NULL;

UPDATE stored_files f
SET product_family_id = p.family_id
FROM products p
WHERE f.product_family_id IS NULL
  AND f.product_id = p.id
  AND f.tenant_id = p.tenant_id;

ALTER TABLE products
    ALTER COLUMN name DROP NOT NULL,
    ALTER COLUMN sale_price DROP NOT NULL,
    ALTER COLUMN currency DROP NOT NULL;
