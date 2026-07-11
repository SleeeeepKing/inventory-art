package com.inventoryart.user;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByUsernameIgnoreCase(String username);
    Optional<User> findByIdAndTenantId(UUID id, UUID tenantId);
    boolean existsByUsernameIgnoreCase(String username);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByRole(UserRole role);
    org.springframework.data.domain.Page<User> findAllByTenantId(UUID tenantId, org.springframework.data.domain.Pageable pageable);
}
