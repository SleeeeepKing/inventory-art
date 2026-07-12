package com.inventoryart.security;

import com.inventoryart.config.AppProperties;
import com.inventoryart.user.User;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
  private final JwtEncoder encoder;
  private final long minutes;

  public JwtService(JwtEncoder encoder, AppProperties properties) {
    this.encoder = encoder;
    this.minutes = properties.getJwt().getAccessTokenMinutes();
  }

  public IssuedToken issue(User user) {
    Instant now = Instant.now();
    Instant expires = now.plusSeconds(minutes * 60);
    JwtClaimsSet.Builder claims =
        JwtClaimsSet.builder()
            .issuer("inventory-art")
            .issuedAt(now)
            .expiresAt(expires)
            .subject(user.getId().toString())
            .claim("username", user.getUsername())
            .claim("role", user.getRole().name())
            .claim("locale", user.getPreferredLocale());
    if (user.getTenantId() != null) claims.claim("tenantId", user.getTenantId().toString());
    String token =
        encoder
            .encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
            .getTokenValue();
    return new IssuedToken(token, expires);
  }

  public record IssuedToken(String value, Instant expiresAt) {}

  public static SecretKey secretKey(AppProperties properties) {
    byte[] bytes = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 32)
      throw new IllegalStateException("JWT_SECRET must contain at least 32 UTF-8 bytes");
    return new SecretKeySpec(bytes, "HmacSHA256");
  }
}
