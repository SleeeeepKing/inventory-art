package com.inventoryart.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryart.tenant.Tenant;
import com.inventoryart.user.User;
import com.inventoryart.user.UserRole;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthDtosTest {
  @Test
  void userResponseIncludesAccountStateAndBusinessFormatting() {
    Tenant tenant =
        new Tenant(UUID.randomUUID(), "Studio", "studio", "EUR", "Europe/Paris", "fr-FR");
    User user =
        new User(
            UUID.randomUUID(),
            tenant.getId(),
            "artist",
            "artist@example.com",
            "hash",
            "Artist",
            UserRole.USER);

    AuthDtos.UserResponse response = AuthDtos.UserResponse.from(user, tenant);

    assertThat(response.enabled()).isTrue();
    assertThat(response.tenantId()).isEqualTo(tenant.getId());
    assertThat(response.tenant())
        .isEqualTo(
            new AuthDtos.TenantSummary(tenant.getId(), "Studio", "EUR", "Europe/Paris", "fr-FR"));
  }

  @Test
  void globalAdministratorHasNoTenantSummary() {
    User admin =
        new User(
            UUID.randomUUID(), null, "admin", "admin@example.com", "hash", "Admin", UserRole.ADMIN);

    AuthDtos.UserResponse response = AuthDtos.UserResponse.from(admin, null);

    assertThat(response.tenantId()).isNull();
    assertThat(response.tenant()).isNull();
  }
}
