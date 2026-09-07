package com.ayush.chat.storage;

import com.ayush.chat.storage.config.ChatStorageProperties;
import com.ayush.chat.storage.provider.MongoChatStorageProvider;
import com.ayush.chat.storage.provider.FirebaseChatStorageProvider;
import com.ayush.chat.storage.provider.SqlChatStorageProvider;
import com.ayush.chat.storage.provider.SupabaseChatStorageProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating and caching ChatStorageProvider instances.
 * 
 * This factory ensures that each storage backend is initialized only once
 * and provides thread-safe access to the active provider.
 * 
 * <p>Usage:</p>
 * <pre>
 * ChatStorageFactory factory = new ChatStorageFactory(properties);
 * ChatStorageProvider provider = factory.getProvider();
 * </pre>
 */
@Slf4j
public class ChatStorageFactory {

    private final ChatStorageProperties properties;
    private final Map<StorageType, ChatStorageProvider> providerCache = new ConcurrentHashMap<>();

    public ChatStorageFactory(ChatStorageProperties properties) {
        this.properties = properties;
    }

    /**
     * Get the ChatStorageProvider for the configured storage type.
     * The provider is created lazily and cached for subsequent calls.
     */
    public ChatStorageProvider getProvider() {
        return getProvider(properties.getType());
    }

    /**
     * Get the ChatStorageProvider for a specific storage type.
     * Creates and caches the provider if not already initialized.
     */
    public ChatStorageProvider getProvider(StorageType type) {
        return providerCache.computeIfAbsent(type, this::createProvider);
    }

    /**
     * Create a new ChatStorageProvider for the given type.
     */
    private ChatStorageProvider createProvider(StorageType type) {
        log.info("Creating ChatStorageProvider for: {}", type.getDisplayName());

        return switch (type) {
            case MYSQL, POSTGRESQL, H2 -> {
                validateSqlConfig(type);
                yield new SqlChatStorageProvider(properties, type);
            }
            case MONGODB -> {
                validateMongoConfig();
                yield new MongoChatStorageProvider(properties);
            }
            case FIREBASE -> {
                validateFirebaseConfig();
                yield new FirebaseChatStorageProvider(properties);
            }
            case SUPABASE -> {
                validateSupabaseConfig();
                yield new SupabaseChatStorageProvider(properties);
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported storage type for chat: " + type.getDisplayName());
        };
    }

    /**
     * Refresh the provider for a specific type (forces re-creation).
     */
    public void refreshProvider(StorageType type) {
        providerCache.remove(type);
        log.info("Refreshed provider for: {}", type.getDisplayName());
        getProvider(type);
    }

    /**
     * Get info about all available providers.
     */
    public Map<StorageType, String> getAvailableProviders() {
        return Map.of(
            StorageType.MYSQL, "MySQL - Relational SQL database",
            StorageType.POSTGRESQL, "PostgreSQL - Advanced SQL database",
            StorageType.MONGODB, "MongoDB - Document-oriented NoSQL",
            StorageType.FIREBASE, "Firebase Firestore - Serverless NoSQL",
            StorageType.SUPABASE, "Supabase - Open source Firebase alternative",
            StorageType.H2, "H2 - Embedded SQL (dev/testing)"
        );
    }

    // ========== Validation Methods ==========

    private void validateSqlConfig(StorageType type) {
        ChatStorageProperties.SqlConfig sql = properties.getSql();
        if (sql.getUrl() == null || sql.getUrl().isEmpty()) {
            throw new IllegalArgumentException(
                    "SQL URL is required for " + type.getDisplayName() + ". " +
                    "Set chat.storage.sql.url in application.yml");
        }
        log.info("[SQL] Using {}: url={}", type.getDisplayName(), sql.getUrl());
    }

    private void validateMongoConfig() {
        ChatStorageProperties.MongoDbConfig mongo = properties.getMongodb();
        if ((mongo.getUri() == null || mongo.getUri().isEmpty()) && 
            (mongo.getHost() == null || mongo.getHost().isEmpty())) {
            throw new IllegalArgumentException(
                    "MongoDB connection URI or host is required. " +
                    "Set chat.storage.mongodb.uri or chat.storage.mongodb.host in application.yml");
        }
        log.info("[MongoDB] Using database: {}", mongo.getDatabase());
    }

    private void validateFirebaseConfig() {
        ChatStorageProperties.FirebaseConfig firebase = properties.getFirebase();
        if (firebase.getProjectId() == null || firebase.getProjectId().isEmpty()) {
            throw new IllegalArgumentException(
                    "Firebase project ID is required. " +
                    "Set chat.storage.firebase.project-id in application.yml");
        }
        log.info("[Firebase] Using project: {}", firebase.getProjectId());
    }

    private void validateSupabaseConfig() {
        ChatStorageProperties.SupabaseConfig supabase = properties.getSupabase();
        if (supabase.getUrl() == null || supabase.getUrl().isEmpty()) {
            throw new IllegalArgumentException(
                    "Supabase URL is required. " +
                    "Set chat.storage.supabase.url in application.yml");
        }
        if (supabase.getApiKey() == null || supabase.getApiKey().isEmpty()) {
            throw new IllegalArgumentException(
                    "Supabase API key is required. " +
                    "Set chat.storage.supabase.api-key in application.yml");
        }
        log.info("[Supabase] Using project: {}", supabase.getUrl());
    }
}
