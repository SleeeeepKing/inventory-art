DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM products WHERE family_id IS NULL) THEN
        RAISE EXCEPTION 'Cannot remove legacy product fields while products.family_id contains null values';
    END IF;
    IF EXISTS (SELECT 1 FROM stored_files WHERE product_family_id IS NULL) THEN
        RAISE EXCEPTION 'Cannot remove legacy file ownership while stored_files.product_family_id contains null values';
    END IF;
END $$;

ALTER TABLE products ALTER COLUMN family_id SET NOT NULL;
ALTER TABLE stored_files ALTER COLUMN product_family_id SET NOT NULL;

ALTER TABLE stored_files DROP CONSTRAINT fk_stored_file_product;

ALTER TABLE products
    DROP COLUMN name,
    DROP COLUMN category,
    DROP COLUMN artist_name,
    DROP COLUMN description,
    DROP COLUMN image_object_key,
    DROP COLUMN sale_price,
    DROP COLUMN cost_price,
    DROP COLUMN currency;

ALTER TABLE stored_files DROP COLUMN product_id;
