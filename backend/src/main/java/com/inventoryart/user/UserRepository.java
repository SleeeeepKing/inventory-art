package com.inventoryart.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByUsernameIgnoreCase(String username);
    Optional<User> findByIdAndTenantId(UUID id, UUID tenantId);
    boolean existsByUsernameIgnoreCase(String username);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByRole(UserRole role);
    org.springframework.data.domain.Page<User> findAllByTenantId(UUID tenantId, org.springframework.data.domain.Pageable pageable);
    @Query("""
        select u from User u
        where (:tenantId is null or u.tenantId=:tenantId)
          and (:q='' or lower(u.username) like lower(concat('%',:q,'%'))
               or lower(u.email) like lower(concat('%',:q,'%'))
               or lower(u.displayName) like lower(concat('%',:q,'%')))
        """)
    org.springframework.data.domain.Page<User> searchAdmin(UUID tenantId, String q,
                                                           org.springframework.data.domain.Pageable pageable);
}
