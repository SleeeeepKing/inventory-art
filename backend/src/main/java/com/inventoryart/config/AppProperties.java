package com.inventoryart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private final Jwt jwt = new Jwt();
    private final Security security = new Security();
    private final Storage storage = new Storage();
    private final ImportConfig importConfig = new ImportConfig();
    private final Seed seed = new Seed();
    public Jwt getJwt() { return jwt; }
    public Security getSecurity() { return security; }
    public Storage getStorage() { return storage; }
    public ImportConfig getImportConfig() { return importConfig; }
    public Seed getSeed() { return seed; }
    public static class Jwt {
        private String secret; private long accessTokenMinutes = 15; private long refreshTokenDays = 30;
        public String getSecret() { return secret; } public void setSecret(String secret) { this.secret = secret; }
        public long getAccessTokenMinutes() { return accessTokenMinutes; } public void setAccessTokenMinutes(long value) { accessTokenMinutes = value; }
        public long getRefreshTokenDays() { return refreshTokenDays; } public void setRefreshTokenDays(long value) { refreshTokenDays = value; }
    }
    public static class Security {
        private String corsAllowedOrigins; private boolean cookieSecure;
        public String getCorsAllowedOrigins() { return corsAllowedOrigins; } public void setCorsAllowedOrigins(String value) { corsAllowedOrigins = value; }
        public boolean isCookieSecure() { return cookieSecure; } public void setCookieSecure(boolean value) { cookieSecure = value; }
    }
    public static class Storage {
        private String provider; private String localPath; private String endpoint; private String region; private String accessKey;
        private String secretKey; private String bucket; private long presignedExpirationSeconds = 900;
        public String getProvider() { return provider; } public void setProvider(String v) { provider=v; }
        public String getLocalPath() { return localPath; } public void setLocalPath(String v) { localPath=v; }
        public String getEndpoint() { return endpoint; } public void setEndpoint(String v) { endpoint=v; }
        public String getRegion() { return region; } public void setRegion(String v) { region=v; }
        public String getAccessKey() { return accessKey; } public void setAccessKey(String v) { accessKey=v; }
        public String getSecretKey() { return secretKey; } public void setSecretKey(String v) { secretKey=v; }
        public String getBucket() { return bucket; } public void setBucket(String v) { bucket=v; }
        public long getPresignedExpirationSeconds() { return presignedExpirationSeconds; } public void setPresignedExpirationSeconds(long v) { presignedExpirationSeconds=v; }
    }
    public static class ImportConfig {
        private long maxFileSize = 20 * 1024 * 1024; private long legacySpreadsheetMaxFileSize = 5 * 1024 * 1024;
        private int batchSize = 200; private int maxRows = 20_000;
        public long getMaxFileSize() { return maxFileSize; } public void setMaxFileSize(long v) { maxFileSize=v; }
        public long getLegacySpreadsheetMaxFileSize() { return legacySpreadsheetMaxFileSize; } public void setLegacySpreadsheetMaxFileSize(long v) { legacySpreadsheetMaxFileSize=v; }
        public int getBatchSize() { return batchSize; } public void setBatchSize(int v) { batchSize=v; }
        public int getMaxRows() { return maxRows; } public void setMaxRows(int v) { maxRows=v; }
    }
    public static class Seed {
        private boolean enabled;
        public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { enabled=v; }
    }
}
