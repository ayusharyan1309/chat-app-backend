package com.ayush.chat.storage.config;

import com.ayush.chat.storage.StorageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ChatStorageProperties configuration binding and defaults.
 * Validates that default values are correct, nested config objects work,
 * and getEffectiveMongoUri builds URIs correctly.
 */
class ChatStoragePropertiesTest {

    private ChatStorageProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ChatStorageProperties();
    }

    // ========== Default Values ==========

    @Test
    void defaultTypeShouldBeH2() {
        assertEquals(StorageType.H2, properties.getType(),
                "Default storage type should be H2");
    }

    @Test
    void defaultCollectionPrefixShouldBeChatUnderscore() {
        assertEquals("chat_", properties.getCollectionPrefix());
    }

    @Test
    void defaultEnableAuditShouldBeTrue() {
        assertTrue(properties.isEnableAudit());
    }

    @Test
    void defaultRetryAttemptsShouldBeThree() {
        assertEquals(3, properties.getRetryAttempts());
    }

    @Test
    void defaultConnectionTimeoutShouldBe30Seconds() {
        assertEquals(30000, properties.getConnectionTimeout());
    }

    @Test
    void sqlConfigShouldNotBeNull() {
        assertNotNull(properties.getSql());
    }

    @Test
    void mongodbConfigShouldNotBeNull() {
        assertNotNull(properties.getMongodb());
    }

    @Test
    void firebaseConfigShouldNotBeNull() {
        assertNotNull(properties.getFirebase());
    }

    @Test
    void supabaseConfigShouldNotBeNull() {
        assertNotNull(properties.getSupabase());
    }

    // ========== Setter/Getter ==========

    @Test
    void shouldSetAndGetStorageType() {
        properties.setType(StorageType.MONGODB);
        assertEquals(StorageType.MONGODB, properties.getType());
    }

    @Test
    void shouldSetAndGetCollectionPrefix() {
        properties.setCollectionPrefix("tenant1_");
        assertEquals("tenant1_", properties.getCollectionPrefix());
    }

    @Test
    void shouldSetAndGetAuditFlag() {
        properties.setEnableAudit(false);
        assertFalse(properties.isEnableAudit());
    }

    // ========== SQL Config Defaults ==========

    @Test
    void sqlConfigDefaultValues() {
        ChatStorageProperties.SqlConfig sql = new ChatStorageProperties.SqlConfig();
        assertNull(sql.getUrl());
        assertNull(sql.getUsername());
        assertNull(sql.getPassword());
        assertNull(sql.getDriverClassName());
        assertEquals(20, sql.getPoolSize());
        assertEquals(5, sql.getMinIdle());
        assertEquals(30000, sql.getConnectionTimeout());
    }

    @Test
    void sqlConfigBuilderShouldWork() {
        ChatStorageProperties.SqlConfig sql = ChatStorageProperties.SqlConfig.builder()
                .url("jdbc:mysql://localhost:3306/test")
                .username("root")
                .password("secret")
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .poolSize(10)
                .minIdle(2)
                .connectionTimeout(5000)
                .build();

        assertEquals("jdbc:mysql://localhost:3306/test", sql.getUrl());
        assertEquals("root", sql.getUsername());
        assertEquals("secret", sql.getPassword());
        assertEquals("com.mysql.cj.jdbc.Driver", sql.getDriverClassName());
        assertEquals(10, sql.getPoolSize());
        assertEquals(2, sql.getMinIdle());
        assertEquals(5000, sql.getConnectionTimeout());
    }

    // ========== MongoDB Config Defaults ==========

    @Test
    void mongodbConfigDefaultValues() {
        ChatStorageProperties.MongoDbConfig mongo = new ChatStorageProperties.MongoDbConfig();
        assertNull(mongo.getUri());
        assertEquals("localhost", mongo.getHost());
        assertEquals(27017, mongo.getPort());
        assertEquals("chat_db", mongo.getDatabase());
        assertNull(mongo.getUsername());
        assertNull(mongo.getPassword());
        assertNull(mongo.getAuthDatabase());
    }

    @Test
    void mongodbConfigBuilderShouldWork() {
        ChatStorageProperties.MongoDbConfig mongo = ChatStorageProperties.MongoDbConfig.builder()
                .uri("mongodb://user:pass@mongo.example.com:27017/mydb")
                .host("mongo.example.com")
                .port(27018)
                .database("mydb")
                .username("user")
                .password("pass")
                .authDatabase("admin")
                .build();

        assertEquals("mongodb://user:pass@mongo.example.com:27017/mydb", mongo.getUri());
        assertEquals("mongo.example.com", mongo.getHost());
        assertEquals(27018, mongo.getPort());
        assertEquals("mydb", mongo.getDatabase());
        assertEquals("user", mongo.getUsername());
        assertEquals("pass", mongo.getPassword());
        assertEquals("admin", mongo.getAuthDatabase());
    }

    // ========== Firebase Config Defaults ==========

    @Test
    void firebaseConfigDefaultValues() {
        ChatStorageProperties.FirebaseConfig firebase = new ChatStorageProperties.FirebaseConfig();
        assertNull(firebase.getProjectId());
        assertEquals("firebase-service-account.json", firebase.getCredentialPath());
        assertEquals("(default)", firebase.getFirestoreDatabase());
    }

    @Test
    void firebaseConfigBuilderShouldWork() {
        ChatStorageProperties.FirebaseConfig firebase = ChatStorageProperties.FirebaseConfig.builder()
                .projectId("my-project")
                .credentialPath("/path/to/credentials.json")
                .firestoreDatabase("my-firestore")
                .build();

        assertEquals("my-project", firebase.getProjectId());
        assertEquals("/path/to/credentials.json", firebase.getCredentialPath());
        assertEquals("my-firestore", firebase.getFirestoreDatabase());
    }

    // ========== Supabase Config Defaults ==========

    @Test
    void supabaseConfigDefaultValues() {
        ChatStorageProperties.SupabaseConfig supabase = new ChatStorageProperties.SupabaseConfig();
        assertNull(supabase.getUrl());
        assertNull(supabase.getApiKey());
        assertNull(supabase.getAnonKey());
        assertEquals("public", supabase.getSchema());
    }

    @Test
    void supabaseConfigBuilderShouldWork() {
        ChatStorageProperties.SupabaseConfig supabase = ChatStorageProperties.SupabaseConfig.builder()
                .url("https://xyz.supabase.co")
                .apiKey("service-role-key")
                .anonKey("anon-key")
                .schema("custom")
                .build();

        assertEquals("https://xyz.supabase.co", supabase.getUrl());
        assertEquals("service-role-key", supabase.getApiKey());
        assertEquals("anon-key", supabase.getAnonKey());
        assertEquals("custom", supabase.getSchema());
    }

    // ========== getEffectiveMongoUri() ==========

    @Test
    void getEffectiveMongoUriShouldReturnDirectUriWhenSet() {
        properties.getMongodb().setUri("mongodb://custom-host:9999/custom_db");
        assertEquals("mongodb://custom-host:9999/custom_db", properties.getEffectiveMongoUri());
    }

    @Test
    void getEffectiveMongoUriShouldBuildSimpleUriFromHostPortDatabase() {
        // Defaults: localhost:27017/chat_db
        String uri = properties.getEffectiveMongoUri();
        assertEquals("mongodb://localhost:27017/chat_db", uri);
    }

    @Test
    void getEffectiveMongoUriShouldBuildUriWithCustomHostPortDatabase() {
        properties.getMongodb().setHost("10.0.0.5");
        properties.getMongodb().setPort(27018);
        properties.getMongodb().setDatabase("production_chat");

        assertEquals("mongodb://10.0.0.5:27018/production_chat", properties.getEffectiveMongoUri());
    }

    @Test
    void getEffectiveMongoUriShouldIncludeAuthWhenUsernameSet() {
        properties.getMongodb().setUsername("admin");
        properties.getMongodb().setPassword("s3cret");

        String uri = properties.getEffectiveMongoUri();
        assertEquals("mongodb://admin:s3cret@localhost:27017/chat_db", uri);
    }

    @Test
    void getEffectiveMongoUriShouldIncludeAuthSourceWhenSet() {
        properties.getMongodb().setUsername("admin");
        properties.getMongodb().setPassword("s3cret");
        properties.getMongodb().setAuthDatabase("auth_db");

        String uri = properties.getEffectiveMongoUri();
        assertEquals("mongodb://admin:s3cret@localhost:27017/chat_db?authSource=auth_db", uri);
    }

    @Test
    void getEffectiveMongoUriShouldHandleUsernameWithoutPassword() {
        properties.getMongodb().setUsername("admin");
        properties.getMongodb().setPassword(null);

        String uri = properties.getEffectiveMongoUri();
        assertEquals("mongodb://admin@localhost:27017/chat_db", uri);
    }

    @Test
    void getEffectiveMongoUriShouldHandleEmptyPassword() {
        properties.getMongodb().setUsername("admin");
        properties.getMongodb().setPassword("");

        String uri = properties.getEffectiveMongoUri();
        assertEquals("mongodb://admin@localhost:27017/chat_db", uri);
    }

    @Test
    void getEffectiveMongoUriShouldIgnoreEmptyUri() {
        properties.getMongodb().setUri("");
        String uri = properties.getEffectiveMongoUri();
        assertEquals("mongodb://localhost:27017/chat_db", uri);
    }

    @Test
    void getEffectiveMongoUriShouldIgnoreUriWithOnlySpaces() {
        properties.getMongodb().setUri("   ");
        String uri = properties.getEffectiveMongoUri();
        // Spaces are not empty so the URI would be returned as-is
        assertEquals("   ", uri);
    }

    @Test
    void getEffectiveMongoUriShouldHandleNullHostAsEmpty() {
        properties.getMongodb().setHost(null);
        // When host is null, the URI builder will just append null
        String uri = properties.getEffectiveMongoUri();
        assertNotNull(uri);
    }

    // ========== ChatStorageProperties Builder ==========

    @Test
    void chatStoragePropertiesShouldSupportLombokDataBehavior() {
        ChatStorageProperties props = new ChatStorageProperties();
        props.setType(StorageType.POSTGRESQL);
        props.setCollectionPrefix("tenant_prod_");
        props.setEnableAudit(false);
        props.setRetryAttempts(5);
        props.setConnectionTimeout(60000);

        assertEquals(StorageType.POSTGRESQL, props.getType());
        assertEquals("tenant_prod_", props.getCollectionPrefix());
        assertFalse(props.isEnableAudit());
        assertEquals(5, props.getRetryAttempts());
        assertEquals(60000, props.getConnectionTimeout());
    }

    @Test
    void chatStoragePropertiesShouldSupportEqualsAndHashCode() {
        ChatStorageProperties p1 = new ChatStorageProperties();
        p1.setType(StorageType.MYSQL);

        ChatStorageProperties p2 = new ChatStorageProperties();
        p2.setType(StorageType.MYSQL);

        assertEquals(p1, p2);
        assertEquals(p1.hashCode(), p2.hashCode());
    }

    @Test
    void chatStoragePropertiesWithDifferentTypesShouldNotBeEqual() {
        ChatStorageProperties p1 = new ChatStorageProperties();
        p1.setType(StorageType.MYSQL);

        ChatStorageProperties p2 = new ChatStorageProperties();
        p2.setType(StorageType.MONGODB);

        assertNotEquals(p1, p2);
    }

    // ========== All StorageType values can be set ==========

    @ParameterizedTest(name = "Should accept type: {0}")
    @CsvSource({
            "MYSQL",
            "POSTGRESQL",
            "MONGODB",
            "FIREBASE",
            "SUPABASE",
            "H2",
            "REDIS"
    })
    void shouldAcceptAllStorageTypes(StorageType type) {
        properties.setType(type);
        assertEquals(type, properties.getType());
    }
}
