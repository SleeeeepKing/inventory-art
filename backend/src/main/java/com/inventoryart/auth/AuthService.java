package com.inventoryart.auth;

import com.inventoryart.config.AppProperties;
import com.inventoryart.audit.AuditLog;
import com.inventoryart.audit.AuditLogRepository;
import com.inventoryart.exception.BusinessException;
import com.inventoryart.security.JwtService;
import com.inventoryart.tenant.Tenant;
import com.inventoryart.tenant.TenantRepository;
import com.inventoryart.user.User;
import com.inventoryart.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthService {
    private final UserRepository users; private final RefreshTokenRepository tokens; private final PasswordEncoder passwords;
    private final JwtService jwt; private final LoginRateLimiter limiter; private final AppProperties properties; private final AuditLogRepository audits;
    private final TenantRepository tenants;
    private final SecureRandom random = new SecureRandom();
    public AuthService(UserRepository users, RefreshTokenRepository tokens, PasswordEncoder passwords, JwtService jwt, LoginRateLimiter limiter, AppProperties properties, AuditLogRepository audits, TenantRepository tenants) {
        this.users=users; this.tokens=tokens; this.passwords=passwords; this.jwt=jwt; this.limiter=limiter; this.properties=properties; this.audits=audits; this.tenants=tenants;
    }
    @Transactional
    public Session login(String username, String password, String ip, String agent) {
        String normalized = username.trim().toLowerCase(); String key = ip + ":" + normalized; limiter.check(key);
        User user = users.findByUsernameIgnoreCase(normalized).filter(User::isEnabled).filter(this::tenantIsEnabled).orElseThrow(this::invalidLogin);
        if (!passwords.matches(password, user.getPasswordHash())) throw invalidLogin();
        user.loginSucceeded(); limiter.success(key); audits.save(new AuditLog(user.getTenantId(),user.getId(),user.getRole(),"LOGIN","USER",user.getId(),"SUCCESS",ip,sanitizeAgent(agent),java.util.Map.of())); return newSession(user, UUID.randomUUID(), ip, agent);
    }
    @Transactional(noRollbackFor = BusinessException.class)
    public Session refresh(String raw, String ip, String agent) {
        if (raw == null || raw.isBlank()) throw new BusinessException("INVALID_REFRESH_TOKEN", "Invalid refresh token", HttpStatus.UNAUTHORIZED);
        RefreshToken existing = tokens.findLockedByTokenHash(hash(raw)).orElseThrow(() -> new BusinessException("INVALID_REFRESH_TOKEN", "Invalid refresh token", HttpStatus.UNAUTHORIZED));
        if (!existing.isActive()) {
            tokens.revokeFamily(existing.getFamilyId(), Instant.now());
            throw new BusinessException("INVALID_REFRESH_TOKEN", "Invalid refresh token", HttpStatus.UNAUTHORIZED);
        }
        User user = users.findById(existing.getUserId()).filter(User::isEnabled).filter(this::tenantIsEnabled).orElseThrow(() -> new BusinessException("INVALID_REFRESH_TOKEN", "Invalid refresh token", HttpStatus.UNAUTHORIZED));
        UUID replacementId = UUID.randomUUID(); existing.revoke(replacementId);
        return newSession(user, existing.getFamilyId(), replacementId, ip, agent);
    }
    @Transactional public void logout(String raw) { if (raw != null) tokens.findByTokenHash(hash(raw)).filter(RefreshToken::isActive).ifPresent(t -> t.revoke(null)); }
    @Transactional public void revokeUser(UUID userId) { tokens.revokeUser(userId, Instant.now()); }
    private Session newSession(User user, UUID family, String ip, String agent) { return newSession(user, family, UUID.randomUUID(), ip, agent); }
    private Session newSession(User user, UUID family, UUID tokenId, String ip, String agent) {
        byte[] bytes = new byte[32]; random.nextBytes(bytes); String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(new RefreshToken(tokenId, user.getId(), family, hash(raw), Instant.now().plusSeconds(properties.getJwt().getRefreshTokenDays()*86400), ip, sanitizeAgent(agent)));
        JwtService.IssuedToken access = jwt.issue(user);
        return new Session(new AuthDtos.AuthResponse(access.value(), access.expiresAt(), userResponse(user)), raw);
    }
    private AuthDtos.UserResponse userResponse(User user) {
        Tenant tenant = user.getTenantId() == null ? null : tenants.findById(user.getTenantId()).orElse(null);
        return AuthDtos.UserResponse.from(user, tenant);
    }
    private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String sanitizeAgent(String agent) { return agent == null ? null : agent.substring(0, Math.min(agent.length(), 500)); }
    private boolean tenantIsEnabled(User user) {
        return user.getTenantId() == null || tenants.findById(user.getTenantId()).filter(t -> t.isEnabled()).isPresent();
    }
    private BusinessException invalidLogin() { return new BusinessException("INVALID_CREDENTIALS", "Invalid username or password", HttpStatus.UNAUTHORIZED); }
    public record Session(AuthDtos.AuthResponse response, String refreshToken) {}
}
