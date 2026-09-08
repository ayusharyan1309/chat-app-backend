package com.ayush.chat.storage.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for platform database configuration.
 * Each platform represents an external data source for user fetching.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformDbRequest {

    /** Unique platform identifier */
    private String id;

    /** Human-readable platform name */
    private String name;

    /** Platform type: supabase, mysql, postgresql, mongodb, firebase, custom */
    private String type;

    /** Whether this platform is active */
    private boolean active;

    /** Supabase-specific config */
    private SupabasePlatformConfig supabase;

    /** SQL-specific config (MySQL, PostgreSQL) */
    private SqlPlatformConfig sql;

    /** MongoDB-specific config */
    private MongoPlatformConfig mongodb;

    /** Firebase-specific config */
    private FirebasePlatformConfig firebase;

    /** Custom REST API config */
    private CustomPlatformConfig custom;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SupabasePlatformConfig {
        private String url;
        private String apiKey;
        private String anonKey;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SqlPlatformConfig {
        private String url;
        private String username;
        private String password;
        private String driverClassName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MongoPlatformConfig {
        private String uri;
        private String database;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FirebasePlatformConfig {
        private String projectId;
        private String credentialPath;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CustomPlatformConfig {
        private String apiUrl;
        private String apiKey;
        private java.util.Map<String, String> headers;
    }
}
