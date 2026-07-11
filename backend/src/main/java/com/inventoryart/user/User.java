package com.inventoryart.user;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import java.util.Locale;

@Entity
@Table(name = "users")
public class User {
    @Id private UUID id;
    @Column(name = "tenant_id") private UUID tenantId;
    @Column(nullable = false, unique = true) private String username;
    @Column(nullable = false, unique = true) private String email;
    @Column(name = "password_hash", nullable = false) private String passwordHash;
    @Column(name = "display_name", nullable = false) private String displayName;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private UserRole role;
    @Column(name = "preferred_locale", nullable = false) private String preferredLocale;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "last_login_at") private Instant lastLoginAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected User() {}
    public User(UUID id, UUID tenantId, String username, String email, String passwordHash, String displayName, UserRole role) {
        this.id = id; this.tenantId = tenantId; this.username = username.trim().toLowerCase(Locale.ROOT); this.email = email.trim().toLowerCase(Locale.ROOT);
        this.passwordHash = passwordHash; this.displayName = displayName; this.role = role;
        this.preferredLocale = "en"; this.enabled = true; this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public UserRole getRole() { return role; }
    public String getPreferredLocale() { return preferredLocale == null ? "en" : preferredLocale; }
    public boolean isEnabled() { return enabled; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void loginSucceeded() { lastLoginAt = Instant.now(); updatedAt = lastLoginAt; }
    public void updateProfile(String displayName, String locale) { this.displayName = displayName; this.preferredLocale = SupportedLocale.fromTag(locale).tag(); this.updatedAt = Instant.now(); }
    public void setEnabled(boolean enabled) { this.enabled = enabled; this.updatedAt = Instant.now(); }
    public void changePassword(String passwordHash) { this.passwordHash = passwordHash; this.updatedAt = Instant.now(); }
}
