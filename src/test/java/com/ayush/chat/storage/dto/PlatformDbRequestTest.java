package com.ayush.chat.storage.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlatformDbRequestTest {

    @Test
    @DisplayName("should build with Supabase config")
    void shouldBuildWithSupabaseConfig() {
        PlatformDbRequest request = PlatformDbRequest.builder()
                .id("supa1")
                .name("Supabase Platform")
                .type("supabase")
                .active(true)
                .supabase(PlatformDbRequest.SupabasePlatformConfig.builder()
                        .url("https://xyz.supabase.co")
                        .apiKey("key123")
                        .anonKey("anon456")
                        .build())
                .build();

        assertEquals("supa1", request.getId());
        assertEquals("supabase", request.getType());
        assertEquals("https://xyz.supabase.co", request.getSupabase().getUrl());
    }

    @Test
    @DisplayName("should build with SQL config")
    void shouldBuildWithSqlConfig() {
        PlatformDbRequest request = PlatformDbRequest.builder()
                .id("sql1")
                .name("SQL Platform")
                .type("mysql")
                .active(true)
                .sql(PlatformDbRequest.SqlPlatformConfig.builder()
                        .url("jdbc:mysql://localhost/db")
                        .username("root")
                        .password("pass")
                        .driverClassName("com.mysql.cj.jdbc.Driver")
                        .build())
                .build();

        assertEquals("mysql", request.getType());
        assertEquals("jdbc:mysql://localhost/db", request.getSql().getUrl());
    }

    @Test
    @DisplayName("should build with MongoDB config")
    void shouldBuildWithMongoConfig() {
        PlatformDbRequest request = PlatformDbRequest.builder()
                .id("mongo1")
                .name("MongoDB Platform")
                .type("mongodb")
                .active(true)
                .mongodb(PlatformDbRequest.MongoPlatformConfig.builder()
                        .uri("mongodb://localhost:27017")
                        .database("chatdb")
                        .build())
                .build();

        assertEquals("mongodb", request.getType());
        assertEquals("mongodb://localhost:27017", request.getMongodb().getUri());
    }

    @Test
    @DisplayName("should build with Firebase config")
    void shouldBuildWithFirebaseConfig() {
        PlatformDbRequest request = PlatformDbRequest.builder()
                .id("fb1")
                .name("Firebase Platform")
                .type("firebase")
                .active(true)
                .firebase(PlatformDbRequest.FirebasePlatformConfig.builder()
                        .projectId("my-project")
                        .credentialPath("/path/cred")
                        .build())
                .build();

        assertEquals("firebase", request.getType());
        assertEquals("my-project", request.getFirebase().getProjectId());
    }

    @Test
    @DisplayName("should build with Custom API config")
    void shouldBuildWithCustomConfig() {
        PlatformDbRequest request = PlatformDbRequest.builder()
                .id("custom1")
                .name("Custom API")
                .type("custom")
                .active(false)
                .custom(PlatformDbRequest.CustomPlatformConfig.builder()
                        .apiUrl("https://api.example.com")
                        .apiKey("apikey")
                        .headers(Map.of("X-Custom", "value"))
                        .build())
                .build();

        assertEquals("custom", request.getType());
        assertFalse(request.isActive());
        assertEquals("https://api.example.com", request.getCustom().getApiUrl());
        assertEquals("value", request.getCustom().getHeaders().get("X-Custom"));
    }

    @Test
    @DisplayName("should support minimal build with only required fields")
    void shouldSupportMinimalBuild() {
        PlatformDbRequest request = PlatformDbRequest.builder()
                .id("minimal")
                .name("Minimal Platform")
                .type("mysql")
                .active(true)
                .build();

        assertNull(request.getSupabase());
        assertNull(request.getSql());
        assertNull(request.getMongodb());
        assertNull(request.getFirebase());
        assertNull(request.getCustom());
    }
}
