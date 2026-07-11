package com.inventoryart.security;

import com.inventoryart.user.UserRole;
import java.util.UUID;

public record CurrentUser(UUID userId, UUID tenantId, String username, UserRole role) {
    public boolean isAdmin() { return role == UserRole.ADMIN; }
}

