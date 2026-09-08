package com.ayush.chat.storage.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for updating the chat storage configuration.
 * Received from the frontend's DatabaseConfigPanel.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorageConfigRequest {

    /** Storage type: mysql, postgresql, mongodb, firebase, supabase, h2 */
    private String type;

    /** SQL-specific configuration */
    private SqlConfigRequest sql;

    /** MongoDB-specific configuration */
    private MongoDbConfigRequest mongodb;

    /** Firebase-specific configuration */
    private FirebaseConfigRequest firebase;

    /** Supabase-specific configuration */
    private SupabaseConfigRequest supabase;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SqlConfigRequest {
        private String url;
        private String username;
        private String password;
        private String driverClassName;
        private int poolSize = 20;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MongoDbConfigRequest {
        private String uri;
        private String host;
        private int port = 27017;
        private String database;
        private String username;
        private String password;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FirebaseConfigRequest {
        private String projectId;
        private String credentialPath;
        private String firestoreDatabase;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SupabaseConfigRequest {
        private String url;
        private String apiKey;
        private String anonKey;
        private String schema;
    }
}
