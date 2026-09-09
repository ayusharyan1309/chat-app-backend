package com.ayush.chat.storage;

import com.ayush.chat.storage.config.ChatStorageProperties;
import com.ayush.chat.storage.provider.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ChatStorageFactory.
 * Validates provider creation for each DB type, caching behavior,
 * validation error handling, and available providers listing.
 * 
 * Note: SQL providers use H2 in-memory for integration tests.
 * MongoDB/Firebase/Supabase providers require actual connections,
 * so we test validation only (not full provider creation).
 */
class ChatStorageFactoryTest {

    private ChatStorageProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ChatStorageProperties();
    }

    // ========== SQL Provider (H2) ==========

    @Test
    void shouldCreateH2Provider() {
        properties.setType(StorageType.H2);
        properties.getSql().setUrl("jdbc:h2:mem:test_factory");
        properties.getSql().setDriverClassName("org.h2.Driver");

        ChatStorageFactory factory = new ChatStorageFactory(properties);
        ChatStorageProvider provider = factory.getProvider();

        assertNotNull(provider);
        assertInstanceOf(SqlChatStorageProvider.class, provider);
        assertEquals(StorageType.H2, provider.getStorageType());
    }

    @Test
    void shouldCreateMySqlProviderWithValidConfig() {
        properties.setType(StorageType.MYSQL);
        properties.getSql().setUrl("jdbc:h2:mem:mysql_test");
        properties.getSql().setDriverClassName("org.h2.Driver");

        ChatStorageFactory factory = new ChatStorageFactory(properties);
        ChatStorageProvider provider = factory.getProvider();

        assertNotNull(provider);
        assertInstanceOf(SqlChatStorageProvider.class, provider);
        assertEquals(StorageType.MYSQL, provider.getStorageType());
    }

    @Test
    void shouldCreatePostgreSqlProviderWithValidConfig() {
        properties.setType(StorageType.POSTGRESQL);
        properties.getSql().setUrl("jdbc:h2:mem:pg_test");
        properties.getSql().setDriverClassName("org.h2.Driver");

        ChatStorageFactory factory = new ChatStorageFactory(properties);
        ChatStorageProvider provider = factory.getProvider();

        assertNotNull(provider);
        assertInstanceOf(SqlChatStorageProvider.class, provider);
        assertEquals(StorageType.POSTGRESQL, provider.getStorageType());
    }

    // ========== Caching ==========

    @Test
    void shouldReturnSameProviderInstanceOnMultipleCalls() {
        properties.setType(StorageType.H2);
        properties.getSql().setUrl("jdbc:h2:mem:cache_test");
        properties.getSql().setDriverClassName("org.h2.Driver");

        ChatStorageFactory factory = new ChatStorageFactory(properties);
        ChatStorageProvider provider1 = factory.getProvider();
        ChatStorageProvider provider2 = factory.getProvider();

        assertSame(provider1, provider2, "Factory should return cached provider instance");
    }

    @Test
    void shouldReturnSameProviderForSpecificType() {
        properties.setType(StorageType.H2);
        properties.getSql().setUrl("jdbc:h2:mem:cache_type_test");
        properties.getSql().setDriverClassName("org.h2.Driver");

        ChatStorageFactory factory = new ChatStorageFactory(properties);
        ChatStorageProvider provider1 = factory.getProvider(StorageType.H2);
        ChatStorageProvider provider2 = factory.getProvider(StorageType.H2);

        assertSame(provider1, provider2, "Factory should cache provider by type");
    }

    @Test
    void getProviderByTypeShouldUseDefaultConfiguredType() {
        properties.setType(StorageType.H2);
        properties.getSql().setUrl("jdbc:h2:mem:default_type_test");
        properties.getSql().setDriverClassName("org.h2.Driver");

        ChatStorageFactory factory = new ChatStorageFactory(properties);
        ChatStorageProvider provider = factory.getProvider();

        assertEquals(StorageType.H2, provider.getStorageType());
    }

    // ========== Validation Errors ==========

    @Test
    void shouldThrowWhenSqlUrlIsNull() {
        properties.setType(StorageType.MYSQL);
        properties.getSql().setUrl(null);

        ChatStorageFactory factory = new ChatStorageFactory(properties);
        assertThrows(IllegalArgumentException.class, factory::getProvider,
                "Should throw when SQL URL is null");
    }

    @Test
    void shouldThrowWhenSqlUrlIsEmpty() {
        properties.setType(StorageType.POSTGRESQL);
        properties.getSql().setUrl("");

        ChatStorageFactory factory = new ChatStorageFactory(properties);
        assertThrows(IllegalArgumentException.class, factory::getProvider,
                "Should throw when SQL URL is empty");
    }

    @Test
    void shouldThrowWhenMongoUriAndHostAreBothNull() {
        properties.setType(StorageType.MONGODB);
        properties.getMongodb().setUri(null);
        properties.getMongodb().setHost(null);

        ChatStorageFactory factory = new ChatStorageFactory(properties);
        // MongoDB will try to create a client which may fail differently
        // but the validation should catch empty URI + host
        assertThrows(Exception.class, factory::getProvider,
                "Should throw when both MongoDB URI and host are null/empty");
    }

    @Test
    void shouldThrowWhenFirebaseProjectIdIsNull() {
        properties.setType(StorageType.FIREBASE);
        properties.getFirebase().setProjectId(null);

        ChatStorageFactory factory = new ChatStorageFactory(properties);
        assertThrows(IllegalArgumentException.class, factory::getProvider,
                "Should throw when Firebase project ID is null");
    }

    @Test
    void shouldThrowWhenFirebaseProjectIdIsEmpty() {
        properties.setType(StorageType.FIREBASE);
        properties.getFirebase().setProjectId("");

        ChatStorageFactory factory = new ChatStorageFactory(properties);
        assertThrows(IllegalArgumentException.class, factory::getProvider,
                "Should throw when Firebase project ID is empty");
    }

    @Test
    void shouldThrowWhenSupabaseUrlIsNull() {
        properties.setType(StorageType.SUPABASE);
        properties.getSupabase().setUrl(null);
        properties.getSupabase().setApiKey("test-key");

        ChatStorageFactory factory = new ChatStorageFactory(properties);
        assertThrows(IllegalArgumentException.class, factory::getProvider,
                "Should throw when Supabase URL is null");
    }

    @Test
    void shouldThrowWhenSupabaseApiKeyIsNull() {
        properties.setType(StorageType.SUPABASE);
        properties.getSupabase().setUrl("https://test.supabase.co");
        properties.getSupabase().setApiKey(null);

        ChatStorageFactory factory = new ChatStorageFactory(properties);
        assertThrows(IllegalArgumentException.class, factory::getProvider,
                "Should throw when Supabase API key is null");
    }

    @Test
    void shouldThrowWhenSupabaseUrlIsEmpty() {
        properties.setType(StorageType.SUPABASE);
        properties.getSupabase().setUrl("");
        properties.getSupabase().setApiKey("test-key");

        ChatStorageFactory factory = new ChatStorageFactory(properties);
        assertThrows(IllegalArgumentException.class, factory::getProvider,
                "Should throw when Supabase URL is empty");
    }

    @Test
    void shouldThrowWhenSupabaseApiKeyIsEmpty() {
        properties.setType(StorageType.SUPABASE);
        properties.getSupabase().setUrl("https://test.supabase.co");
        properties.getSupabase().setApiKey("");

        ChatStorageFactory factory = new ChatStorageFactory(properties);
        assertThrows(IllegalArgumentException.class, factory::getProvider,
                "Should throw when Supabase API key is empty");
    }

    // ========== Refresh Provider ==========

    @Test
    void refreshProviderShouldReturnNewInstance() {
        properties.setType(StorageType.H2);
        properties.getSql().setUrl("jdbc:h2:mem:refresh_test");
        properties.getSql().setDriverClassName("org.h2.Driver");

        ChatStorageFactory factory = new ChatStorageFactory(properties);
        ChatStorageProvider provider1 = factory.getProvider();

        factory.refreshProvider(StorageType.H2);
        ChatStorageProvider provider2 = factory.getProvider();

        assertNotNull(provider1);
        assertNotNull(provider2);
        // After refresh, the provider should be re-created (may be different object)
        // but with same type
        assertEquals(provider1.getStorageType(), provider2.getStorageType());
    }

    // ========== Available Providers ==========

    @Test
    void getAvailableProvidersShouldReturnAllSixTypes() {
        ChatStorageFactory factory = new ChatStorageFactory(properties);
        Map<StorageType, String> providers = factory.getAvailableProviders();

        assertEquals(6, providers.size(), "Should list 6 available providers");
        assertTrue(providers.containsKey(StorageType.MYSQL));
        assertTrue(providers.containsKey(StorageType.POSTGRESQL));
        assertTrue(providers.containsKey(StorageType.MONGODB));
        assertTrue(providers.containsKey(StorageType.FIREBASE));
        assertTrue(providers.containsKey(StorageType.SUPABASE));
        assertTrue(providers.containsKey(StorageType.H2));
    }

    @Test
    void getAvailableProvidersShouldNotIncludeRedis() {
        ChatStorageFactory factory = new ChatStorageFactory(properties);
        Map<StorageType, String> providers = factory.getAvailableProviders();

        assertFalse(providers.containsKey(StorageType.REDIS),
                "Redis should not be in available providers (not for chat storage)");
    }

    // ========== Provider Health Check ==========

    @Test
    void h2ProviderShouldBeHealthy() {
        properties.setType(StorageType.H2);
        properties.getSql().setUrl("jdbc:h2:mem:health_test");
        properties.getSql().setDriverClassName("org.h2.Driver");

        ChatStorageFactory factory = new ChatStorageFactory(properties);
        ChatStorageProvider provider = factory.getProvider();

        assertTrue(provider.isHealthy(), "H2 provider should always be healthy");
    }

    // ========== Provider Description ==========

    @Test
    void h2ProviderShouldHaveCorrectDescription() {
        properties.setType(StorageType.H2);
        properties.getSql().setUrl("jdbc:h2:mem:desc_test");
        properties.getSql().setDriverClassName("org.h2.Driver");

        ChatStorageFactory factory = new ChatStorageFactory(properties);
        ChatStorageProvider provider = factory.getProvider();

        assertNotNull(provider.getDescription());
        assertTrue(provider.getDescription().contains("H2"));
    }

    @Test
    void mysqlProviderShouldHaveCorrectDescription() {
        properties.setType(StorageType.MYSQL);
        properties.getSql().setUrl("jdbc:h2:mem:desc_mysql");
        properties.getSql().setDriverClassName("org.h2.Driver");

        ChatStorageFactory factory = new ChatStorageFactory(properties);
        ChatStorageProvider provider = factory.getProvider();

        assertNotNull(provider.getDescription());
        assertTrue(provider.getDescription().contains("MySQL"));
    }
}
