package com.inventoryart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
  private final Jwt jwt = new Jwt();
  private final Security security = new Security();
  private final Storage storage = new Storage();
  private final Seed seed = new Seed();

  public Jwt getJwt() {
    return jwt;
  }

  public Security getSecurity() {
    return security;
  }

  public Storage getStorage() {
    return storage;
  }

  public Seed getSeed() {
    return seed;
  }

  public static class Jwt {
    private String secret;
    private long accessTokenMinutes = 15;
    private long refreshTokenDays = 30;

    public String getSecret() {
      return secret;
    }

    public void setSecret(String secret) {
      this.secret = secret;
    }

    public long getAccessTokenMinutes() {
      return accessTokenMinutes;
    }

    public void setAccessTokenMinutes(long value) {
      accessTokenMinutes = value;
    }

    public long getRefreshTokenDays() {
      return refreshTokenDays;
    }

    public void setRefreshTokenDays(long value) {
      refreshTokenDays = value;
    }
  }

  public static class Security {
    private String corsAllowedOrigins;
    private boolean cookieSecure;
    private String cookieSameSite = "Lax";

    public String getCorsAllowedOrigins() {
      return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(String value) {
      corsAllowedOrigins = value;
    }

    public boolean isCookieSecure() {
      return cookieSecure;
    }

    public void setCookieSecure(boolean value) {
      cookieSecure = value;
    }

    public String getCookieSameSite() {
      return cookieSameSite;
    }

    public void setCookieSameSite(String value) {
      cookieSameSite = value;
    }
  }

  public static class Storage {
    private String provider;
    private String localPath;
    private String endpoint;
    private String publicEndpoint;
    private String region;
    private String accessKey;
    private String secretKey;
    private String bucket;
    private long presignedExpirationSeconds = 900;

    public String getProvider() {
      return provider;
    }

    public void setProvider(String v) {
      provider = v;
    }

    public String getLocalPath() {
      return localPath;
    }

    public void setLocalPath(String v) {
      localPath = v;
    }

    public String getEndpoint() {
      return endpoint;
    }

    public void setEndpoint(String v) {
      endpoint = v;
    }

    public String getPublicEndpoint() {
      return publicEndpoint;
    }

    public void setPublicEndpoint(String v) {
      publicEndpoint = v;
    }

    public String getRegion() {
      return region;
    }

    public void setRegion(String v) {
      region = v;
    }

    public String getAccessKey() {
      return accessKey;
    }

    public void setAccessKey(String v) {
      accessKey = v;
    }

    public String getSecretKey() {
      return secretKey;
    }

    public void setSecretKey(String v) {
      secretKey = v;
    }

    public String getBucket() {
      return bucket;
    }

    public void setBucket(String v) {
      bucket = v;
    }

    public long getPresignedExpirationSeconds() {
      return presignedExpirationSeconds;
    }

    public void setPresignedExpirationSeconds(long v) {
      presignedExpirationSeconds = v;
    }
  }

  public static class Seed {
    private boolean enabled;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean v) {
      enabled = v;
    }
  }
}
