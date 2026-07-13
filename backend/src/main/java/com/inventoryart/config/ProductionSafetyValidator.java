package com.inventoryart.config;

import java.net.URI;
import java.util.Arrays;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Prevents a production deployment from silently using local development security defaults. */
@Component
@Profile("prod")
public class ProductionSafetyValidator implements InitializingBean {
  private static final String DEVELOPMENT_JWT_SECRET =
      "local-development-secret-change-me-1234567890";
  private final AppProperties properties;
  private final boolean localProdDebug;

  public ProductionSafetyValidator(AppProperties properties, Environment environment) {
    this.properties = properties;
    localProdDebug = environment.acceptsProfiles(Profiles.of("local-prod-debug"));
  }

  @Override
  public void afterPropertiesSet() {
    String jwtSecret = properties.getJwt().getSecret();
    String normalizedSecret = jwtSecret == null ? "" : jwtSecret.toLowerCase(java.util.Locale.ROOT);
    if (!StringUtils.hasText(jwtSecret)
        || DEVELOPMENT_JWT_SECRET.equals(jwtSecret)
        || normalizedSecret.contains("change-me")
        || normalizedSecret.startsWith("replace-")
        || jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException(
          "Production requires a unique JWT_SECRET with at least 32 UTF-8 bytes");
    }
    if (!localProdDebug && !properties.getSecurity().isCookieSecure()) {
      throw new IllegalStateException("Production requires COOKIE_SECURE=true");
    }
    String origins = properties.getSecurity().getCorsAllowedOrigins();
    if (!StringUtils.hasText(origins)
        || Arrays.stream(origins.split(","))
            .map(String::trim)
            .anyMatch(origin -> isInvalidOrigin(origin, localProdDebug))) {
      throw new IllegalStateException(
          "Production CORS_ALLOWED_ORIGINS must contain only exact HTTPS origins");
    }
    AppProperties.Storage storage = properties.getStorage();
    if (!"r2".equalsIgnoreCase(storage.getProvider())
        || !StringUtils.hasText(storage.getEndpoint())
        || !StringUtils.hasText(storage.getAccessKey())
        || !StringUtils.hasText(storage.getSecretKey())
        || !StringUtils.hasText(storage.getBucket())) {
      throw new IllegalStateException(
          "Production requires complete private R2 storage configuration");
    }
    if (properties.getSeed().isEnabled()) {
      throw new IllegalStateException("Production seed data must remain disabled");
    }
  }

  private static boolean isInvalidOrigin(String origin, boolean localProdDebug) {
    if (origin.contains("*")) return true;
    String normalizedOrigin = origin.toLowerCase(java.util.Locale.ROOT);
    if (normalizedOrigin.contains("localhost") || normalizedOrigin.contains("127.0.0.1")) {
      return !localProdDebug || !isExactLoopbackOrigin(origin);
    }
    return !normalizedOrigin.startsWith("https://");
  }

  private static boolean isExactLoopbackOrigin(String origin) {
    try {
      URI uri = URI.create(origin);
      String host = uri.getHost();
      String path = uri.getRawPath();
      boolean http = "http".equalsIgnoreCase(uri.getScheme());
      boolean https = "https".equalsIgnoreCase(uri.getScheme());
      boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
      int port = uri.getPort();
      boolean validPort = port == -1 || (port > 0 && port <= 65535);
      boolean exactOrigin =
          uri.getUserInfo() == null
              && (path == null || path.isEmpty())
              && uri.getRawQuery() == null
              && uri.getRawFragment() == null;
      return (http || https) && loopback && validPort && exactOrigin;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }
}
