package com.inventoryart.security;

import com.inventoryart.exception.BusinessException;
import com.inventoryart.tenant.TenantRepository;
import com.inventoryart.user.User;
import com.inventoryart.user.UserRepository;
import com.inventoryart.user.UserRole;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserService {
  private final UserRepository users;
  private final TenantRepository tenants;

  public CurrentUserService(UserRepository users, TenantRepository tenants) {
    this.users = users;
    this.tenants = tenants;
  }

  public CurrentUser get() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt))
      throw new BusinessException(
          "UNAUTHENTICATED", "Authentication required", HttpStatus.UNAUTHORIZED);
    try {
      UUID userId = UUID.fromString(jwt.getSubject());
      String tenantClaim = jwt.getClaimAsString("tenantId");
      UUID tenantId = tenantClaim == null ? null : UUID.fromString(tenantClaim);
      UserRole role = UserRole.valueOf(jwt.getClaimAsString("role"));
      User user =
          users
              .findById(userId)
              .filter(User::isEnabled)
              .orElseThrow(() -> unauthenticated("Account is disabled or no longer exists"));
      if (user.getRole() != role || !java.util.Objects.equals(user.getTenantId(), tenantId))
        throw unauthenticated("Authentication context is no longer valid");
      if (tenantId != null && tenants.findById(tenantId).filter(t -> t.isEnabled()).isEmpty())
        throw new BusinessException(
            "TENANT_DISABLED", "Tenant is disabled or no longer exists", HttpStatus.FORBIDDEN);
      return new CurrentUser(userId, tenantId, user.getUsername(), role);
    } catch (IllegalArgumentException ex) {
      throw unauthenticated("Invalid authentication context");
    }
  }

  public UUID tenantId() {
    CurrentUser user = get();
    if (user.tenantId() == null)
      throw new BusinessException(
          "TENANT_REQUIRED", "Tenant context required", HttpStatus.FORBIDDEN);
    return user.tenantId();
  }

  public UUID userId() {
    return get().userId();
  }

  private BusinessException unauthenticated(String message) {
    return new BusinessException("UNAUTHENTICATED", message, HttpStatus.UNAUTHORIZED);
  }
}
