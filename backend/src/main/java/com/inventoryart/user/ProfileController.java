package com.inventoryart.user;

import com.inventoryart.audit.AuditService;
import com.inventoryart.auth.AuthDtos;
import com.inventoryart.auth.AuthService;
import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.security.CurrentUserService;
import com.inventoryart.tenant.Tenant;
import com.inventoryart.tenant.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {
  private final CurrentUserService current;
  private final UserRepository users;
  private final TenantRepository tenants;
  private final PasswordEncoder passwords;
  private final AuthService auth;
  private final AuditService audit;

  public ProfileController(
      CurrentUserService current,
      UserRepository users,
      TenantRepository tenants,
      PasswordEncoder passwords,
      AuthService auth,
      AuditService audit) {
    this.current = current;
    this.users = users;
    this.tenants = tenants;
    this.passwords = passwords;
    this.auth = auth;
    this.audit = audit;
  }

  @GetMapping
  public AuthDtos.UserResponse me() {
    User user = users.findById(current.userId()).orElseThrow(() -> new NotFoundException("User"));
    return response(user);
  }

  @PatchMapping
  @Transactional
  public AuthDtos.UserResponse update(@Valid @RequestBody ProfileUpdate request) {
    User user = users.findById(current.userId()).orElseThrow(() -> new NotFoundException("User"));
    user.updateProfile(request.displayName(), request.preferredLocale());
    audit.record(user.getTenantId(), "PROFILE_UPDATE", "USER", user.getId(), "SUCCESS", Map.of());
    return response(user);
  }

  @PostMapping("/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Transactional
  public void password(@Valid @RequestBody PasswordChange request) {
    User user = users.findById(current.userId()).orElseThrow(() -> new NotFoundException("User"));
    if (!passwords.matches(request.currentPassword(), user.getPasswordHash()))
      throw new BusinessException("INVALID_PASSWORD", "Current password is incorrect");
    user.changePassword(passwords.encode(request.newPassword()));
    auth.revokeUser(user.getId());
    audit.record(user.getTenantId(), "PASSWORD_CHANGE", "USER", user.getId(), "SUCCESS", Map.of());
  }

  private AuthDtos.UserResponse response(User user) {
    Tenant tenant =
        user.getTenantId() == null ? null : tenants.findById(user.getTenantId()).orElse(null);
    return AuthDtos.UserResponse.from(user, tenant);
  }

  public record ProfileUpdate(
      @NotBlank @Size(max = 160) String displayName, @NotBlank String preferredLocale) {}

  public record PasswordChange(
      @NotBlank String currentPassword, @NotBlank @Size(min = 10, max = 100) String newPassword) {}
}
