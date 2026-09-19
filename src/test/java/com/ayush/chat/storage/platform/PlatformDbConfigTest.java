package com.ayush.chat.storage.platform;

import com.ayush.chat.storage.dto.PlatformDbRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlatformDbConfigTest {

    // ==================== fromRequest ====================

    @Nested
    @DisplayName("fromRequest conversion")
    class FromRequest {

        @Test
        @DisplayName("should create config from Supabase request")
        void shouldCreateFromSupabaseRequest() {
            PlatformDbRequest request = PlatformDbRequest.builder()
                    .id("supa1")
                    .name("My Supabase")
                    .type("supabase")
                    .active(true)
                    .supabase(PlatformDbRequest.SupabasePlatformConfig.builder()
                            .url("https://xyz.supabase.co")
                            .apiKey("key123")
                            .anonKey("anon456")
                            .build())
                    .build();

            PlatformDbConfig config = PlatformDbConfig.fromRequest(request);

            assertEquals("supa1", config.getId());
            assertEquals("My Supabase", config.getName());
            assertEquals("supabase", config.getType());
            assertTrue(config.isActive());
            assertEquals("https://xyz.supabase.co", config.getSupabaseUrl());
            assertEquals("key123", config.getSupabaseApiKey());
            assertEquals("anon456", config.getSupabaseAnonKey());
        }

        @Test
        @DisplayName("should create config from MySQL request")
        void shouldCreateFromMySqlRequest() {
            PlatformDbRequest request = PlatformDbRequest.builder()
                    .id("mysql1")
                    .name("MySQL DB")
                    .type("mysql")
                    .active(true)
                    .sql(PlatformDbRequest.SqlPlatformConfig.builder()
                            .url("jdbc:mysql://localhost:3306/db")
                            .username("root")
                            .password("pass")
                            .driverClassName("com.mysql.cj.jdbc.Driver")
                            .build())
                    .build();

            PlatformDbConfig config = PlatformDbConfig.fromRequest(request);

            assertEquals("mysql", config.getType());
            assertEquals("jdbc:mysql://localhost:3306/db", config.getSqlUrl());
            assertEquals("root", config.getSqlUsername());
            assertEquals("pass", config.getSqlPassword());
            assertEquals("com.mysql.cj.jdbc.Driver", config.getSqlDriverClassName());
        }

        @Test
        @DisplayName("should create config from PostgreSQL request")
        void shouldCreateFromPostgresRequest() {
            PlatformDbRequest request = PlatformDbRequest.builder()
                    .id("pg1")
                    .name("PostgreSQL")
                    .type("postgresql")
                    .active(true)
                    .sql(PlatformDbRequest.SqlPlatformConfig.builder()
                            .url("jdbc:postgresql://localhost:5432/db")
                            .username("postgres")
                            .password("pgpass")
                            .build())
                    .build();

            PlatformDbConfig config = PlatformDbConfig.fromRequest(request);

            assertEquals("postgresql", config.getType());
            assertEquals("jdbc:postgresql://localhost:5432/db", config.getSqlUrl());
        }

        @Test
        @DisplayName("should create config from MongoDB request")
        void shouldCreateFromMongoRequest() {
            PlatformDbRequest request = PlatformDbRequest.builder()
                    .id("mongo1")
                    .name("MongoDB")
                    .type("mongodb")
                    .active(true)
                    .mongodb(PlatformDbRequest.MongoPlatformConfig.builder()
                            .uri("mongodb://localhost:27017")
                            .database("chatdb")
                            .build())
                    .build();

            PlatformDbConfig config = PlatformDbConfig.fromRequest(request);

            assertEquals("mongodb", config.getType());
            assertEquals("mongodb://localhost:27017", config.getMongoUri());
            assertEquals("chatdb", config.getMongoDatabase());
        }

        @Test
        @DisplayName("should create config from Firebase request")
        void shouldCreateFromFirebaseRequest() {
            PlatformDbRequest request = PlatformDbRequest.builder()
                    .id("fb1")
                    .name("Firebase")
                    .type("firebase")
                    .active(true)
                    .firebase(PlatformDbRequest.FirebasePlatformConfig.builder()
                            .projectId("my-project")
                            .credentialPath("/path/to/cred")
                            .build())
                    .build();

            PlatformDbConfig config = PlatformDbConfig.fromRequest(request);

            assertEquals("firebase", config.getType());
            assertEquals("my-project", config.getFirebaseProjectId());
            assertEquals("/path/to/cred", config.getFirebaseCredentialPath());
        }

        @Test
        @DisplayName("should create config from Custom API request")
        void shouldCreateFromCustomApiRequest() {
            PlatformDbRequest request = PlatformDbRequest.builder()
                    .id("custom1")
                    .name("Custom API")
                    .type("custom")
                    .active(false)
                    .custom(PlatformDbRequest.CustomPlatformConfig.builder()
                            .apiUrl("https://api.example.com")
                            .apiKey("apikey123")
                            .headers(Map.of("X-Custom", "value"))
                            .build())
                    .build();

            PlatformDbConfig config = PlatformDbConfig.fromRequest(request);

            assertEquals("custom", config.getType());
            assertFalse(config.isActive());
            assertEquals("https://api.example.com", config.getCustomApiUrl());
            assertEquals("apikey123", config.getCustomApiKey());
            assertEquals("value", config.getCustomHeaders().get("X-Custom"));
        }

        @Test
        @DisplayName("should handle null sub-configs gracefully")
        void shouldHandleNullSubConfigs() {
            PlatformDbRequest request = PlatformDbRequest.builder()
                    .id("minimal")
                    .name("Minimal")
                    .type("mysql")
                    .active(true)
                    // All sub-configs null
                    .build();

            PlatformDbConfig config = PlatformDbConfig.fromRequest(request);

            assertEquals("minimal", config.getId());
            assertNull(config.getSqlUrl());
            assertNull(config.getSupabaseUrl());
            assertNull(config.getMongoUri());
        }
    }

    // ==================== toResponse ====================

    @Nested
    @DisplayName("toResponse conversion")
    class ToResponse {

        @Test
        @DisplayName("should convert Supabase config to response")
        void shouldConvertSupabaseToResponse() {
            PlatformDbConfig config = PlatformDbConfig.builder()
                    .id("supa1")
                    .name("Supabase")
                    .type("supabase")
                    .active(true)
                    .supabaseUrl("https://xyz.supabase.co")
                    .supabaseApiKey("key123")
                    .supabaseAnonKey("anon456")
                    .build();

            PlatformDbRequest response = config.toResponse();

            assertEquals("supa1", response.getId());
            assertEquals("supabase", response.getType());
            assertNotNull(response.getSupabase());
            assertEquals("https://xyz.supabase.co", response.getSupabase().getUrl());
            assertEquals("key123", response.getSupabase().getApiKey());
        }

        @Test
        @DisplayName("should convert MySQL config to response")
        void shouldConvertMySqlToResponse() {
            PlatformDbConfig config = PlatformDbConfig.builder()
                    .id("mysql1")
                    .name("MySQL")
                    .type("mysql")
                    .active(true)
                    .sqlUrl("jdbc:mysql://localhost/db")
                    .sqlUsername("root")
                    .sqlPassword("pass")
                    .sqlDriverClassName("com.mysql.cj.jdbc.Driver")
                    .build();

            PlatformDbRequest response = config.toResponse();

            assertNotNull(response.getSql());
            assertEquals("jdbc:mysql://localhost/db", response.getSql().getUrl());
            assertEquals("root", response.getSql().getUsername());
        }

        @Test
        @DisplayName("should convert PostgreSQL config to response")
        void shouldConvertPostgresToResponse() {
            PlatformDbConfig config = PlatformDbConfig.builder()
                    .id("pg1")
                    .name("PostgreSQL")
                    .type("postgresql")
                    .active(true)
                    .sqlUrl("jdbc:postgresql://localhost/db")
                    .build();

            PlatformDbRequest response = config.toResponse();

            // PostgreSQL uses SQL config
            assertNotNull(response.getSql());
            assertEquals("jdbc:postgresql://localhost/db", response.getSql().getUrl());
        }

        @Test
        @DisplayName("should convert MongoDB config to response")
        void shouldConvertMongoToResponse() {
            PlatformDbConfig config = PlatformDbConfig.builder()
                    .id("mongo1")
                    .name("MongoDB")
                    .type("mongodb")
                    .active(true)
                    .mongoUri("mongodb://localhost:27017")
                    .mongoDatabase("chatdb")
                    .build();

            PlatformDbRequest response = config.toResponse();

            assertNotNull(response.getMongodb());
            assertEquals("mongodb://localhost:27017", response.getMongodb().getUri());
            assertEquals("chatdb", response.getMongodb().getDatabase());
        }

        @Test
        @DisplayName("should convert Firebase config to response")
        void shouldConvertFirebaseToResponse() {
            PlatformDbConfig config = PlatformDbConfig.builder()
                    .id("fb1")
                    .name("Firebase")
                    .type("firebase")
                    .active(true)
                    .firebaseProjectId("my-project")
                    .firebaseCredentialPath("/path/cred")
                    .build();

            PlatformDbRequest response = config.toResponse();

            assertNotNull(response.getFirebase());
            assertEquals("my-project", response.getFirebase().getProjectId());
            assertEquals("/path/cred", response.getFirebase().getCredentialPath());
        }

        @Test
        @DisplayName("should convert Custom config to response")
        void shouldConvertCustomToResponse() {
            PlatformDbConfig config = PlatformDbConfig.builder()
                    .id("custom1")
                    .name("Custom API")
                    .type("custom")
                    .active(false)
                    .customApiUrl("https://api.example.com")
                    .customApiKey("apikey")
                    .customHeaders(Map.of("X-Custom", "value"))
                    .build();

            PlatformDbRequest response = config.toResponse();

            assertNotNull(response.getCustom());
            assertEquals("https://api.example.com", response.getCustom().getApiUrl());
            assertEquals("apikey", response.getCustom().getApiKey());
            assertEquals("value", response.getCustom().getHeaders().get("X-Custom"));
        }

        @Test
        @DisplayName("should not set sub-configs for non-matching type")
        void shouldNotSetSubConfigsForNonMatchingType() {
            PlatformDbConfig config = PlatformDbConfig.builder()
                    .id("sql_only")
                    .name("SQL Only")
                    .type("mysql")
                    .active(true)
                    .sqlUrl("jdbc:mysql://localhost/db")
                    .supabaseUrl("should not appear")
                    .build();

            PlatformDbRequest response = config.toResponse();

            assertNotNull(response.getSql());
            assertNull(response.getSupabase(), "Supabase config should not appear for MySQL type");
        }
    }

    // ==================== Round-trip ====================

    @Nested
    @DisplayName("Round-trip conversion")
    class RoundTrip {

        @Test
        @DisplayName("should round-trip Supabase config")
        void shouldRoundTripSupabaseConfig() {
            PlatformDbRequest original = PlatformDbRequest.builder()
                    .id("rt_supa")
                    .name("RT Supabase")
                    .type("supabase")
                    .active(true)
                    .supabase(PlatformDbRequest.SupabasePlatformConfig.builder()
                            .url("https://xyz.supabase.co")
                            .apiKey("key123")
                            .anonKey("anon456")
                            .build())
                    .build();

            PlatformDbConfig config = PlatformDbConfig.fromRequest(original);
            PlatformDbRequest result = config.toResponse();

            assertEquals(original.getId(), result.getId());
            assertEquals(original.getName(), result.getName());
            assertEquals(original.getType(), result.getType());
            assertEquals(original.isActive(), result.isActive());
            assertEquals(original.getSupabase().getUrl(), result.getSupabase().getUrl());
            assertEquals(original.getSupabase().getApiKey(), result.getSupabase().getApiKey());
        }

        @Test
        @DisplayName("should round-trip MySQL config")
        void shouldRoundTripMySqlConfig() {
            PlatformDbRequest original = PlatformDbRequest.builder()
                    .id("rt_mysql")
                    .name("RT MySQL")
                    .type("mysql")
                    .active(true)
                    .sql(PlatformDbRequest.SqlPlatformConfig.builder()
                            .url("jdbc:mysql://localhost/db")
                            .username("root")
                            .password("pass")
                            .driverClassName("com.mysql.cj.jdbc.Driver")
                            .build())
                    .build();

            PlatformDbConfig config = PlatformDbConfig.fromRequest(original);
            PlatformDbRequest result = config.toResponse();

            assertEquals(original.getSql().getUrl(), result.getSql().getUrl());
            assertEquals(original.getSql().getUsername(), result.getSql().getUsername());
            assertEquals(original.getSql().getPassword(), result.getSql().getPassword());
        }

        @Test
        @DisplayName("should round-trip Firebase config")
        void shouldRoundTripFirebaseConfig() {
            PlatformDbRequest original = PlatformDbRequest.builder()
                    .id("rt_fb")
                    .name("RT Firebase")
                    .type("firebase")
                    .active(true)
                    .firebase(PlatformDbRequest.FirebasePlatformConfig.builder()
                            .projectId("my-proj")
                            .credentialPath("/path/cred")
                            .build())
                    .build();

            PlatformDbConfig config = PlatformDbConfig.fromRequest(original);
            PlatformDbRequest result = config.toResponse();

            assertEquals(original.getFirebase().getProjectId(), result.getFirebase().getProjectId());
            assertEquals(original.getFirebase().getCredentialPath(), result.getFirebase().getCredentialPath());
        }
    }
}
