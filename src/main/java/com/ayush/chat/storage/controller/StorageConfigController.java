package com.ayush.chat.storage.controller;

import com.ayush.chat.storage.ChatStorageFactory;
import com.ayush.chat.storage.StorageType;
import com.ayush.chat.storage.config.ChatStorageProperties;
import com.ayush.chat.storage.dto.ConnectionTestResponse;
import com.ayush.chat.storage.dto.StorageConfigRequest;
import com.ayush.chat.storage.dto.StorageTypeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * REST controller for chat storage configuration.
 * 
 * Endpoints:
 * - GET  /api/admin/storage/config    — Get current config
 * - PUT  /api/admin/storage/config    — Update config
 * - POST /api/admin/storage/test      — Test connection
 * - GET  /api/admin/storage/types     — List available types
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/storage")
@RequiredArgsConstructor
public class StorageConfigController {

    private final ChatStorageProperties properties;
    private final ChatStorageFactory storageFactory;

    /**
     * Get the current active storage configuration.
     */
    @GetMapping("/config")
    public ResponseEntity<StorageConfigRequest> getConfig() {
        StorageConfigRequest config = StorageConfigRequest.builder()
                .type(properties.getType().getCode())
                .build();

        // Include current config for the active type
        if (properties.getType().isSqlBased()) {
            config.setSql(StorageConfigRequest.SqlConfigRequest.builder()
                    .url(properties.getSql().getUrl())
                    .username(properties.getSql().getUsername())
                    .password(properties.getSql().getPassword())
                    .driverClassName(properties.getSql().getDriverClassName())
                    .poolSize(properties.getSql().getPoolSize())
                    .build());
        } else if (properties.getType() == StorageType.MONGODB) {
            config.setMongodb(StorageConfigRequest.MongoDbConfigRequest.builder()
                    .uri(properties.getMongodb().getUri())
                    .host(properties.getMongodb().getHost())
                    .port(properties.getMongodb().getPort())
                    .database(properties.getMongodb().getDatabase())
                    .username(properties.getMongodb().getUsername())
                    .password(properties.getMongodb().getPassword())
                    .build());
        } else if (properties.getType() == StorageType.FIREBASE) {
            config.setFirebase(StorageConfigRequest.FirebaseConfigRequest.builder()
                    .projectId(properties.getFirebase().getProjectId())
                    .credentialPath(properties.getFirebase().getCredentialPath())
                    .firestoreDatabase(properties.getFirebase().getFirestoreDatabase())
                    .build());
        } else if (properties.getType() == StorageType.SUPABASE) {
            config.setSupabase(StorageConfigRequest.SupabaseConfigRequest.builder()
                    .url(properties.getSupabase().getUrl())
                    .apiKey(properties.getSupabase().getApiKey())
                    .anonKey(properties.getSupabase().getAnonKey())
                    .schema(properties.getSupabase().getSchema())
                    .build());
        }

        return ResponseEntity.ok(config);
    }

    /**
     * Update the chat storage backend configuration.
     * This updates the in-memory config and recreates the storage provider.
     */
    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody StorageConfigRequest request) {
        log.info("Updating chat storage config to type: {}", request.getType());

        try {
            StorageType newType = StorageType.fromCode(request.getType());

            // Update properties based on the new type
            properties.setType(newType);

            if (request.getSql() != null && newType.isSqlBased()) {
                properties.setSql(ChatStorageProperties.SqlConfig.builder()
                        .url(request.getSql().getUrl())
                        .username(request.getSql().getUsername())
                        .password(request.getSql().getPassword())
                        .driverClassName(request.getSql().getDriverClassName())
                        .poolSize(request.getSql().getPoolSize())
                        .build());
            }
            if (request.getMongodb() != null && newType == StorageType.MONGODB) {
                properties.setMongodb(ChatStorageProperties.MongoDbConfig.builder()
                        .uri(request.getMongodb().getUri())
                        .host(request.getMongodb().getHost())
                        .port(request.getMongodb().getPort())
                        .database(request.getMongodb().getDatabase())
                        .username(request.getMongodb().getUsername())
                        .password(request.getMongodb().getPassword())
                        .build());
            }
            if (request.getFirebase() != null && newType == StorageType.FIREBASE) {
                properties.setFirebase(ChatStorageProperties.FirebaseConfig.builder()
                        .projectId(request.getFirebase().getProjectId())
                        .credentialPath(request.getFirebase().getCredentialPath())
                        .firestoreDatabase(request.getFirebase().getFirestoreDatabase())
                        .build());
            }
            if (request.getSupabase() != null && newType == StorageType.SUPABASE) {
                properties.setSupabase(ChatStorageProperties.SupabaseConfig.builder()
                        .url(request.getSupabase().getUrl())
                        .apiKey(request.getSupabase().getApiKey())
                        .anonKey(request.getSupabase().getAnonKey())
                        .schema(request.getSupabase().getSchema())
                        .build());
            }

            // Refresh the storage provider with new config
            storageFactory.refreshProvider(newType);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Storage configuration updated to " + newType.getDisplayName()
            ));
        } catch (Exception e) {
            log.error("Failed to update storage config", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Failed to update config: " + e.getMessage()
            ));
        }
    }

    /**
     * Test the current storage connection.
     */
    @PostMapping("/test")
    public ResponseEntity<ConnectionTestResponse> testConnection() {
        try {
            var provider = storageFactory.getProvider();
            boolean healthy = provider.isHealthy();
            String type = provider.getStorageType().getDisplayName();

            if (healthy) {
                return ResponseEntity.ok(ConnectionTestResponse.success(type, "Connection successful"));
            } else {
                return ResponseEntity.ok(ConnectionTestResponse.failure(type, "Connection unhealthy"));
            }
        } catch (Exception e) {
            log.error("Connection test failed", e);
            String type = properties.getType().getDisplayName();
            return ResponseEntity.ok(ConnectionTestResponse.failure(type, "Connection failed: " + e.getMessage()));
        }
    }

    /**
     * Get available storage types.
     */
    @GetMapping("/types")
    public ResponseEntity<List<StorageTypeResponse>> getAvailableTypes() {
        List<StorageTypeResponse> types = Stream.of(StorageType.values())
                .filter(StorageType::supportsChatStorage)
                .map(t -> StorageTypeResponse.of(t.getCode(), t.getDisplayName(), t.isSqlBased() ? "Relational SQL" : "NoSQL/Document"))
                .toList();
        return ResponseEntity.ok(types);
    }
}
