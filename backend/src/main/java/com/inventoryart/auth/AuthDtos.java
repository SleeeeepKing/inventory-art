package com.inventoryart.auth;

import com.inventoryart.tenant.Tenant;
import com.inventoryart.user.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {
  private AuthDtos() {}

  public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

  public record ChangePasswordRequest(
      @NotBlank String currentPassword, @NotBlank @Size(min = 10, max = 100) String newPassword) {}

  public record TenantSummary(
      UUID id, String name, String defaultCurrency, String timezone, String locale) {
    public static TenantSummary from(Tenant tenant) {
      return tenant == null
          ? null
          : new TenantSummary(
              tenant.getId(),
              tenant.getName(),
              tenant.getDefaultCurrency(),
              tenant.getTimezone(),
              tenant.getLocale());
    }
  }

  public record UserResponse(
      UUID id,
      UUID tenantId,
      String username,
      String email,
      String displayName,
      String role,
      String preferredLocale,
      boolean enabled,
      TenantSummary tenant) {
    public static UserResponse from(User user, Tenant tenant) {
      return new UserResponse(
          user.getId(),
          user.getTenantId(),
          user.getUsername(),
          user.getEmail(),
          user.getDisplayName(),
          user.getRole().name(),
          user.getPreferredLocale(),
          user.isEnabled(),
          TenantSummary.from(tenant));
    }
  }

  public record AuthResponse(String accessToken, Instant expiresAt, UserResponse user) {}
}
