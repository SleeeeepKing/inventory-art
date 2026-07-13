ALTER TABLE stored_files
    ADD COLUMN preview_object_key VARCHAR(700),
    ADD COLUMN preview_content_type VARCHAR(160),
    ADD COLUMN preview_size BIGINT,
    ADD COLUMN preview_checksum VARCHAR(64);

CREATE UNIQUE INDEX uq_stored_files_preview_object_key
    ON stored_files(preview_object_key)
    WHERE preview_object_key IS NOT NULL;
