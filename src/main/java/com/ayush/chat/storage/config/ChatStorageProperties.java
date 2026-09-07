package com.ayush.chat.storage.config;

import com.ayush.chat.storage.StorageType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the chat storage backend.
 * 
 * Configure in application.yml:
 * <pre>
 * chat:
 *   storage:
 *     type: mongodb          # Switch to: mysql, postgresql, mongodb, firebase, supabase, h2
 *     collection-prefix: chat_
 *     enable-audit: true
 *     retry-attempts: 3
 *     
 *     # SQL-specific (mysql, postgresql, h2)
 *     sql:
 *       url: jdbc:mysql://localhost:3306/chat_db
 *       username: root
 *       password: secret
 *       driver-class-name: com.mysql.cj.jdbc.Driver
 *       pool-size: 20
 *     
 *     # MongoDB-specific
 *     mongodb:
 *       uri: mongodb://localhost:27017/chat_db
 *       database: chat_db
 *     
 *     # Firebase-specific
 *     firebase:
 *       project-id: my-project
 *       credential-path: firebase-service-account.json
 *     
 *     # Supabase-specific
 *     supabase:
 *       url: https://xyzcompany.supabase.co
 *       api-key: eyJhbGciOi...
 *       anon-key: eyJhbGciOi...
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "chat.storage")
public class ChatStorageProperties {

    /**
     * The storage backend type to use.
     * Supported: mysql, postgresql, mongodb, firebase, supabase, h2
     */
    private StorageType type = StorageType.H2;

    /**
     * Prefix for collection/table names (useful for multi-tenant isolation).
     */
    private String collectionPrefix = "chat_";

    /**
     * Enable audit logging for all storage operations.
     */
    private boolean enableAudit = true;

    /**
     * Number of retry attempts for failed storage operations.
     */
    private int retryAttempts = 3;

    /**
     * Connection timeout in milliseconds.
     */
    private long connectionTimeout = 30000;

    /**
     * SQL-specific configuration.
     */
    private SqlConfig sql = new SqlConfig();

    /**
     * MongoDB-specific configuration.
     */
    private MongoDbConfig mongodb = new MongoDbConfig();

    /**
     * Firebase-specific configuration.
     */
    private FirebaseConfig firebase = new FirebaseConfig();

    /**
     * Supabase-specific configuration.
     */
    private SupabaseConfig supabase = new SupabaseConfig();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SqlConfig {
        private String url;
        private String username;
        private String password;
        private String driverClassName;
        
        @Builder.Default
        private int poolSize = 20;
        
        @Builder.Default
        private int minIdle = 5;
        
        @Builder.Default
        private long connectionTimeout = 30000;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MongoDbConfig {
        /** Full MongoDB connection URI (overrides host/port/database if set). */
        private String uri;
        
        /** MongoDB host (used if uri is not set). */
        @Builder.Default
        private String host = "localhost";
        
        /** MongoDB port (used if uri is not set). */
        @Builder.Default
        private int port = 27017;
        
        /** MongoDB database name (used if uri is not set). */
        @Builder.Default
        private String database = "chat_db";
        
        /** MongoDB authentication username. */
        private String username;
        
        /** MongoDB authentication password. */
        private String password;
        
        /** Authentication database. */
        private String authDatabase;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FirebaseConfig {
        /** Google Cloud project ID. */
        private String projectId;
        
        /** Path to the Firebase service account JSON file. */
        @Builder.Default
        private String credentialPath = "firebase-service-account.json";
        
        /** Firestore database name (default or named database). */
        @Builder.Default
        private String firestoreDatabase = "(default)";
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SupabaseConfig {
        /** Supabase project URL (e.g., https://xyz.supabase.co). */
        private String url;
        
        /** Supabase API key (service_role key for server-side). */
        private String apiKey;
        
        /** Supabase anon key (public anon key). */
        private String anonKey;
        
        /** Schema to use (default: public). */
        @Builder.Default
        private String schema = "public";
    }

    /**
     * Get the effective connection URI for MongoDB.
     */
    public String getEffectiveMongoUri() {
        if (mongodb.getUri() != null && !mongodb.getUri().isEmpty()) {
            return mongodb.getUri();
        }
        StringBuilder sb = new StringBuilder("mongodb://");
        if (mongodb.getUsername() != null && !mongodb.getUsername().isEmpty()) {
            sb.append(mongodb.getUsername());
            if (mongodb.getPassword() != null && !mongodb.getPassword().isEmpty()) {
                sb.append(":").append(mongodb.getPassword());
            }
            sb.append("@");
        }
        sb.append(mongodb.getHost()).append(":").append(mongodb.getPort());
        sb.append("/").append(mongodb.getDatabase());
        if (mongodb.getAuthDatabase() != null && !mongodb.getAuthDatabase().isEmpty()) {
            sb.append("?authSource=").append(mongodb.getAuthDatabase());
        }
        return sb.toString();
    }
}
