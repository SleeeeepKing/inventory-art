package com.inventoryart.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "family_id", nullable = false)
  private UUID familyId;

  @Column(name = "token_hash", nullable = false, unique = true, length = 64)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "replaced_by")
  private UUID replacedBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_ip")
  private String createdIp;

  @Column(name = "user_agent")
  private String userAgent;

  protected RefreshToken() {}

  public RefreshToken(
      UUID id,
      UUID userId,
      UUID familyId,
      String tokenHash,
      Instant expiresAt,
      String ip,
      String userAgent) {
    this.id = id;
    this.userId = userId;
    this.familyId = familyId;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
    this.createdAt = Instant.now();
    this.createdIp = ip;
    this.userAgent = userAgent;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getFamilyId() {
    return familyId;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public boolean isActive() {
    return revokedAt == null && expiresAt.isAfter(Instant.now());
  }

  public void revoke(UUID replacement) {
    this.revokedAt = Instant.now();
    this.replacedBy = replacement;
  }
}
