ALTER TABLE inventory_movements
    ADD CONSTRAINT fk_movement_order
    FOREIGN KEY (tenant_id, related_order_id)
    REFERENCES orders (tenant_id, id);
