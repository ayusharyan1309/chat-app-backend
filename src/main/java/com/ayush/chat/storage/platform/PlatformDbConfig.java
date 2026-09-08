package com.ayush.chat.storage.platform;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * In-memory model for platform database configurations.
 * Stores connection details for external user-fetching databases.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformDbConfig {

    private String id;
    private String name;
    private String type; // supabase, mysql, postgresql, mongodb, firebase, custom
    private boolean active;

    // Supabase
    private String supabaseUrl;
    private String supabaseApiKey;
    private String supabaseAnonKey;

    // SQL (MySQL, PostgreSQL)
    private String sqlUrl;
    private String sqlUsername;
    private String sqlPassword;
    private String sqlDriverClassName;

    // MongoDB
    private String mongoUri;
    private String mongoDatabase;

    // Firebase
    private String firebaseProjectId;
    private String firebaseCredentialPath;

    // Custom REST API
    private String customApiUrl;
    private String customApiKey;
    private Map<String, String> customHeaders;

    /**
     * Create from a DTO request.
     */
    public static PlatformDbConfig fromRequest(com.ayush.chat.storage.dto.PlatformDbRequest req) {
        PlatformDbConfigBuilder builder = PlatformDbConfig.builder()
                .id(req.getId())
                .name(req.getName())
                .type(req.getType())
                .active(req.isActive());

        if (req.getSupabase() != null) {
            builder.supabaseUrl(req.getSupabase().getUrl())
                    .supabaseApiKey(req.getSupabase().getApiKey())
                    .supabaseAnonKey(req.getSupabase().getAnonKey());
        }
        if (req.getSql() != null) {
            builder.sqlUrl(req.getSql().getUrl())
                    .sqlUsername(req.getSql().getUsername())
                    .sqlPassword(req.getSql().getPassword())
                    .sqlDriverClassName(req.getSql().getDriverClassName());
        }
        if (req.getMongodb() != null) {
            builder.mongoUri(req.getMongodb().getUri())
                    .mongoDatabase(req.getMongodb().getDatabase());
        }
        if (req.getFirebase() != null) {
            builder.firebaseProjectId(req.getFirebase().getProjectId())
                    .firebaseCredentialPath(req.getFirebase().getCredentialPath());
        }
        if (req.getCustom() != null) {
            builder.customApiUrl(req.getCustom().getApiUrl())
                    .customApiKey(req.getCustom().getApiKey())
                    .customHeaders(req.getCustom().getHeaders());
        }

        return builder.build();
    }

    /**
     * Convert to API response format (matching frontend PlatformDbConfig type).
     */
    public com.ayush.chat.storage.dto.PlatformDbRequest toResponse() {
        com.ayush.chat.storage.dto.PlatformDbRequest req = com.ayush.chat.storage.dto.PlatformDbRequest.builder()
                .id(id)
                .name(name)
                .type(type)
                .active(active)
                .build();

        if ("supabase".equals(type)) {
            req.setSupabase(com.ayush.chat.storage.dto.PlatformDbRequest.SupabasePlatformConfig.builder()
                    .url(supabaseUrl)
                    .apiKey(supabaseApiKey)
                    .anonKey(supabaseAnonKey)
                    .build());
        } else if ("mysql".equals(type) || "postgresql".equals(type)) {
            req.setSql(com.ayush.chat.storage.dto.PlatformDbRequest.SqlPlatformConfig.builder()
                    .url(sqlUrl)
                    .username(sqlUsername)
                    .password(sqlPassword)
                    .driverClassName(sqlDriverClassName)
                    .build());
        } else if ("mongodb".equals(type)) {
            req.setMongodb(com.ayush.chat.storage.dto.PlatformDbRequest.MongoPlatformConfig.builder()
                    .uri(mongoUri)
                    .database(mongoDatabase)
                    .build());
        } else if ("firebase".equals(type)) {
            req.setFirebase(com.ayush.chat.storage.dto.PlatformDbRequest.FirebasePlatformConfig.builder()
                    .projectId(firebaseProjectId)
                    .credentialPath(firebaseCredentialPath)
                    .build());
        } else if ("custom".equals(type)) {
            req.setCustom(com.ayush.chat.storage.dto.PlatformDbRequest.CustomPlatformConfig.builder()
                    .apiUrl(customApiUrl)
                    .apiKey(customApiKey)
                    .headers(customHeaders)
                    .build());
        }

        return req;
    }
}
