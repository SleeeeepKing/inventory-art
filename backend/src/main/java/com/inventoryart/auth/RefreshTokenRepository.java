package com.inventoryart.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
  Optional<RefreshToken> findByTokenHash(String tokenHash);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
  Optional<RefreshToken> findLockedByTokenHash(String tokenHash);

  @Modifying
  @Query(
      "update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
  int revokeFamily(UUID familyId, Instant now);

  @Modifying
  @Query(
      "update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
  int revokeUser(UUID userId, Instant now);
}
