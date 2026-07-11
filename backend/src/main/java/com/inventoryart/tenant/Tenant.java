package com.inventoryart.tenant;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
public class Tenant {
    @Id private UUID id;
    @Column(nullable = false) private String name;
    @Column(nullable = false, unique = true) private String slug;
    @Column(name = "default_currency", nullable = false, length = 3) private String defaultCurrency;
    @Column(nullable = false) private String timezone;
    @Column(nullable = false) private String locale;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected Tenant() {}
    public Tenant(UUID id, String name, String slug, String defaultCurrency, String timezone, String locale) {
        this.id = id; this.name = name; this.slug = slug; this.defaultCurrency = defaultCurrency;
        this.timezone = timezone; this.locale = locale; this.enabled = true;
        this.createdAt = Instant.now(); this.updatedAt = this.createdAt;
    }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getDefaultCurrency() { return defaultCurrency; }
    public String getTimezone() { return timezone; }
    public String getLocale() { return locale; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; this.updatedAt = Instant.now(); }
    public void update(String name, String defaultCurrency, String timezone, String locale) {
        this.name = name; this.defaultCurrency = defaultCurrency; this.timezone = timezone; this.locale = locale; this.updatedAt = Instant.now();
    }
}

