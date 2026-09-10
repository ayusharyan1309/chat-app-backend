package com.ayush.chat.storage.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserResponseTest {

    @Test
    @DisplayName("should create from Supabase factory method")
    void shouldCreateFromSupabase() {
        UserResponse user = UserResponse.fromSupabase("id1", "alice@test.com", "Alice", "https://avatar.com/alice");

        assertEquals("id1", user.getId());
        assertEquals("alice@test.com", user.getEmail());
        assertEquals("Alice", user.getFullName());
        assertEquals("https://avatar.com/alice", user.getAvatarUrl());
        assertEquals("supabase", user.getSource());
        assertNull(user.getPlatformId());
    }

    @Test
    @DisplayName("should create from platform factory method")
    void shouldCreateFromPlatform() {
        UserResponse user = UserResponse.fromPlatform("u1", "bob@test.com", "Bob", "platform_123");

        assertEquals("u1", user.getId());
        assertEquals("bob@test.com", user.getEmail());
        assertEquals("Bob", user.getFullName());
        assertEquals("platform_123", user.getPlatformId());
        assertEquals("platform_123", user.getSource());
        assertNull(user.getAvatarUrl());
    }

    @Test
    @DisplayName("should handle null values in factory methods")
    void shouldHandleNullValues() {
        UserResponse user = UserResponse.fromSupabase(null, null, null, null);

        assertNull(user.getId());
        assertNull(user.getEmail());
        assertNull(user.getFullName());
        assertNull(user.getAvatarUrl());
    }

    @Test
    @DisplayName("should support builder pattern")
    void shouldSupportBuilder() {
        UserResponse user = UserResponse.builder()
                .id("u1")
                .email("test@test.com")
                .fullName("Test User")
                .avatarUrl("https://avatar.com/test")
                .platformId("p1")
                .source("mysql")
                .build();

        assertEquals("u1", user.getId());
        assertEquals("test@test.com", user.getEmail());
        assertEquals("mysql", user.getSource());
    }

    @Test
    @DisplayName("should support all-args constructor")
    void shouldSupportAllArgsConstructor() {
        UserResponse user = new UserResponse("u1", "a@b.com", "Name", "avatar", "p1", "src");

        assertEquals("u1", user.getId());
        assertEquals("a@b.com", user.getEmail());
        assertEquals("Name", user.getFullName());
        assertEquals("avatar", user.getAvatarUrl());
        assertEquals("p1", user.getPlatformId());
        assertEquals("src", user.getSource());
    }
}
