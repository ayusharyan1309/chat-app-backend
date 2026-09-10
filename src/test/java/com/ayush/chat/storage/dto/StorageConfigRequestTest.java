package com.ayush.chat.storage.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StorageConfigRequestTest {

    @Test
    @DisplayName("should build with SQL config")
    void shouldBuildWithSqlConfig() {
        StorageConfigRequest request = StorageConfigRequest.builder()
                .type("mysql")
                .sql(StorageConfigRequest.SqlConfigRequest.builder()
                        .url("jdbc:mysql://localhost/db")
                        .username("root")
                        .password("pass")
                        .driverClassName("com.mysql.cj.jdbc.Driver")
                        .poolSize(20)
                        .build())
                .build();

        assertEquals("mysql", request.getType());
        assertEquals("jdbc:mysql://localhost/db", request.getSql().getUrl());
        assertEquals("root", request.getSql().getUsername());
        assertEquals(20, request.getSql().getPoolSize());
    }

    @Test
    @DisplayName("should build with MongoDB config")
    void shouldBuildWithMongoDbConfig() {
        StorageConfigRequest request = StorageConfigRequest.builder()
                .type("mongodb")
                .mongodb(StorageConfigRequest.MongoDbConfigRequest.builder()
                        .uri("mongodb://localhost:27017")
                        .host("localhost")
                        .port(27017)
                        .database("chatdb")
                        .username("admin")
                        .password("secret")
                        .build())
                .build();

        assertEquals("mongodb", request.getType());
        assertEquals("mongodb://localhost:27017", request.getMongodb().getUri());
        assertEquals(27017, request.getMongodb().getPort());
    }

    @Test
    @DisplayName("should build with Firebase config")
    void shouldBuildWithFirebaseConfig() {
        StorageConfigRequest request = StorageConfigRequest.builder()
                .type("firebase")
                .firebase(StorageConfigRequest.FirebaseConfigRequest.builder()
                        .projectId("my-project")
                        .credentialPath("/path/to/cred.json")
                        .firestoreDatabase("(default)")
                        .build())
                .build();

        assertEquals("firebase", request.getType());
        assertEquals("my-project", request.getFirebase().getProjectId());
        assertEquals("(default)", request.getFirebase().getFirestoreDatabase());
    }

    @Test
    @DisplayName("should build with Supabase config")
    void shouldBuildWithSupabaseConfig() {
        StorageConfigRequest request = StorageConfigRequest.builder()
                .type("supabase")
                .supabase(StorageConfigRequest.SupabaseConfigRequest.builder()
                        .url("https://xyz.supabase.co")
                        .apiKey("key123")
                        .anonKey("anon456")
                        .schema("public")
                        .build())
                .build();

        assertEquals("supabase", request.getType());
        assertEquals("https://xyz.supabase.co", request.getSupabase().getUrl());
        assertEquals("public", request.getSupabase().getSchema());
    }

    @Test
    @DisplayName("should set pool size explicitly on SQL config")
    void shouldSetPoolSizeExplicitly() {
        StorageConfigRequest.SqlConfigRequest sqlConfig = StorageConfigRequest.SqlConfigRequest.builder()
                .url("jdbc:h2:mem:test")
                .poolSize(20)
                .build();

        assertEquals(20, sqlConfig.getPoolSize());
    }

    @Test
    @DisplayName("should set port explicitly on MongoDB config")
    void shouldSetPortExplicitly() {
        StorageConfigRequest.MongoDbConfigRequest mongoConfig = StorageConfigRequest.MongoDbConfigRequest.builder()
                .uri("mongodb://localhost")
                .port(27017)
                .build();

        assertEquals(27017, mongoConfig.getPort());
    }

    @Test
    @DisplayName("should support null sub-configs")
    void shouldSupportNullSubConfigs() {
        StorageConfigRequest request = StorageConfigRequest.builder()
                .type("h2")
                .build();

        assertEquals("h2", request.getType());
        assertNull(request.getSql());
        assertNull(request.getMongodb());
        assertNull(request.getFirebase());
        assertNull(request.getSupabase());
    }
}
