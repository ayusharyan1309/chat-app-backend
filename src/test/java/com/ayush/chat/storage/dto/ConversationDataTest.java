package com.ayush.chat.storage.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ConversationData DTO.
 * Validates builder pattern, default values, Lombok-generated methods,
 * helper methods (getOtherUserId, isParticipant, isUserBlocked, etc.).
 */
@DisplayName("ConversationData DTO Tests")
class ConversationDataTest {

    @Test
    @DisplayName("Should create conversation with builder pattern")
    void shouldCreateWithBuilder() {
        ConversationData conv = ConversationData.builder()
                .id("conv-1")
                .user1Id("user-1")
                .user1Email("user1@test.com")
                .user2Id("user-2")
                .user2Email("user2@test.com")
                .lastMessage("Hi there!")
                .lastMessageTime(Instant.now())
                .status("ACTIVE")
                .blockedByUser1(false)
                .blockedByUser2(false)
                .messageCount(10)
                .unreadCountUser1(2)
                .unreadCountUser2(0)
                .tenantId("tenant-1")
                .build();

        assertEquals("conv-1", conv.getId());
        assertEquals("user-1", conv.getUser1Id());
        assertEquals("user1@test.com", conv.getUser1Email());
        assertEquals("user-2", conv.getUser2Id());
        assertEquals("user2@test.com", conv.getUser2Email());
        assertEquals("Hi there!", conv.getLastMessage());
        assertNotNull(conv.getLastMessageTime());
        assertEquals("ACTIVE", conv.getStatus());
        assertFalse(conv.isBlockedByUser1());
        assertFalse(conv.isBlockedByUser2());
        assertEquals(10, conv.getMessageCount());
        assertEquals(2, conv.getUnreadCountUser1());
        assertEquals(0, conv.getUnreadCountUser2());
        assertEquals("tenant-1", conv.getTenantId());
    }

    @Test
    @DisplayName("Default values should be correct")
    void shouldHaveCorrectDefaults() {
        ConversationData conv = new ConversationData();

        assertNull(conv.getId());
        assertNull(conv.getUser1Id());
        assertNull(conv.getUser2Id());
        assertEquals("INIT", conv.getStatus(), "Default status should be INIT");
        assertFalse(conv.isBlockedByUser1());
        assertFalse(conv.isBlockedByUser2());
        assertEquals(0, conv.getMessageCount());
        assertEquals(0, conv.getUnreadCountUser1());
        assertEquals(0, conv.getUnreadCountUser2());
    }

    // ========== Helper Method Tests ==========

    @Test
    @DisplayName("getOtherUserId should return other user when given user1")
    void getOtherUserIdShouldReturnUser2WhenGivenUser1() {
        ConversationData conv = ConversationData.builder()
                .user1Id("alice").user2Id("bob").build();

        assertEquals("bob", conv.getOtherUserId("alice"));
    }

    @Test
    @DisplayName("getOtherUserId should return user1 when given user2")
    void getOtherUserIdShouldReturnUser1WhenGivenUser2() {
        ConversationData conv = ConversationData.builder()
                .user1Id("alice").user2Id("bob").build();

        assertEquals("alice", conv.getOtherUserId("bob"));
    }

    @Test
    @DisplayName("getOtherUserId should return null for unknown user")
    void getOtherUserIdShouldReturnNullForUnknownUser() {
        ConversationData conv = ConversationData.builder()
                .user1Id("alice").user2Id("bob").build();

        assertNull(conv.getOtherUserId("charlie"));
    }

    @Test
    @DisplayName("getOtherUserEmail should return other email when given user1 email")
    void getOtherUserEmailShouldReturnUser2EmailWhenGivenUser1Email() {
        ConversationData conv = ConversationData.builder()
                .user1Email("alice@test.com").user2Email("bob@test.com").build();

        assertEquals("bob@test.com", conv.getOtherUserEmail("alice@test.com"));
    }

    @Test
    @DisplayName("getOtherUserEmail should return user1 email when given user2 email")
    void getOtherUserEmailShouldReturnUser1EmailWhenGivenUser2Email() {
        ConversationData conv = ConversationData.builder()
                .user1Email("alice@test.com").user2Email("bob@test.com").build();

        assertEquals("alice@test.com", conv.getOtherUserEmail("bob@test.com"));
    }

    @Test
    @DisplayName("isParticipant should return true for user1")
    void isParticipantShouldReturnTrueForUser1() {
        ConversationData conv = ConversationData.builder()
                .user1Id("alice").user2Id("bob").build();

        assertTrue(conv.isParticipant("alice"));
    }

    @Test
    @DisplayName("isParticipant should return true for user2")
    void isParticipantShouldReturnTrueForUser2() {
        ConversationData conv = ConversationData.builder()
                .user1Id("alice").user2Id("bob").build();

        assertTrue(conv.isParticipant("bob"));
    }

    @Test
    @DisplayName("isParticipant should return false for non-participant")
    void isParticipantShouldReturnFalseForNonParticipant() {
        ConversationData conv = ConversationData.builder()
                .user1Id("alice").user2Id("bob").build();

        assertFalse(conv.isParticipant("charlie"));
    }

    @Test
    @DisplayName("isUserBlocked should return blockedByUser2 when user is user1")
    void isUserBlockedShouldCheckBlockedByUser2ForUser1() {
        ConversationData conv = ConversationData.builder()
                .user1Id("alice").user2Id("bob")
                .blockedByUser1(false).blockedByUser2(true).build();

        assertTrue(conv.isUserBlocked("alice"), "Alice should be blocked (by Bob)");
    }

    @Test
    @DisplayName("isUserBlocked should return blockedByUser1 when user is user2")
    void isUserBlockedShouldCheckBlockedByUser1ForUser2() {
        ConversationData conv = ConversationData.builder()
                .user1Id("alice").user2Id("bob")
                .blockedByUser1(true).blockedByUser2(false).build();

        assertTrue(conv.isUserBlocked("bob"), "Bob should be blocked (by Alice)");
    }

    @Test
    @DisplayName("isUserBlocked should return false when not blocked")
    void isUserBlockedShouldReturnFalseWhenNotBlocked() {
        ConversationData conv = ConversationData.builder()
                .user1Id("alice").user2Id("bob")
                .blockedByUser1(false).blockedByUser2(false).build();

        assertFalse(conv.isUserBlocked("alice"));
        assertFalse(conv.isUserBlocked("bob"));
    }

    @Test
    @DisplayName("isUserBlocked should return false for non-participant")
    void isUserBlockedShouldReturnFalseForNonParticipant() {
        ConversationData conv = ConversationData.builder()
                .user1Id("alice").user2Id("bob")
                .blockedByUser1(true).blockedByUser2(true).build();

        assertFalse(conv.isUserBlocked("charlie"));
    }

    // ========== Equals and HashCode ==========

    @Test
    @DisplayName("Equal conversations should have same equals and hashCode")
    void shouldSupportEqualsAndHashCode() {
        Instant now = Instant.now();
        ConversationData c1 = ConversationData.builder()
                .id("c1").user1Id("u1").user2Id("u2").createdAt(now).build();
        ConversationData c2 = ConversationData.builder()
                .id("c1").user1Id("u1").user2Id("u2").createdAt(now).build();

        assertEquals(c1, c2);
        assertEquals(c1.hashCode(), c2.hashCode());
    }

    @Test
    @DisplayName("Different conversations should not be equal")
    void shouldNotBeEqualForDifferentData() {
        ConversationData c1 = ConversationData.builder().id("c1").build();
        ConversationData c2 = ConversationData.builder().id("c2").build();

        assertNotEquals(c1, c2);
    }

    // ========== Setter/Getter ==========

    @Test
    @DisplayName("Should support all setters and getters")
    void shouldSupportSettersAndGetters() {
        ConversationData conv = new ConversationData();
        Instant now = Instant.now();

        conv.setId("id-1");
        conv.setUser1Id("u1");
        conv.setUser1Email("u1@test.com");
        conv.setUser2Id("u2");
        conv.setUser2Email("u2@test.com");
        conv.setLastMessage("last msg");
        conv.setLastMessageTime(now);
        conv.setStatus("ACTIVE");
        conv.setBlockedByUser1(true);
        conv.setBlockedByUser2(false);
        conv.setMessageCount(5);
        conv.setUnreadCountUser1(3);
        conv.setUnreadCountUser2(1);
        conv.setCreatedAt(now);
        conv.setUpdatedAt(now);
        conv.setTenantId("tenant-1");

        assertEquals("id-1", conv.getId());
        assertEquals("u1", conv.getUser1Id());
        assertEquals("u1@test.com", conv.getUser1Email());
        assertEquals("u2", conv.getUser2Id());
        assertEquals("u2@test.com", conv.getUser2Email());
        assertEquals("last msg", conv.getLastMessage());
        assertEquals(now, conv.getLastMessageTime());
        assertEquals("ACTIVE", conv.getStatus());
        assertTrue(conv.isBlockedByUser1());
        assertFalse(conv.isBlockedByUser2());
        assertEquals(5, conv.getMessageCount());
        assertEquals(3, conv.getUnreadCountUser1());
        assertEquals(1, conv.getUnreadCountUser2());
        assertEquals(now, conv.getCreatedAt());
        assertEquals(now, conv.getUpdatedAt());
        assertEquals("tenant-1", conv.getTenantId());
    }

    @Test
    @DisplayName("All-arg constructor should set all fields")
    void allArgConstructorShouldSetAllFields() {
        Instant now = Instant.now();
        ConversationData conv = new ConversationData(
                "id", "u1", "u1@t.com", "u2", "u2@t.com",
                "last", now, "ACTIVE", true, false,
                5, 2, 0, now, now, null, "t1"
        );

        assertEquals("id", conv.getId());
        assertEquals("u1", conv.getUser1Id());
        assertEquals("u1@t.com", conv.getUser1Email());
        assertEquals("u2", conv.getUser2Id());
        assertEquals("u2@t.com", conv.getUser2Email());
        assertEquals("last", conv.getLastMessage());
        assertEquals(now, conv.getLastMessageTime());
        assertEquals("ACTIVE", conv.getStatus());
        assertTrue(conv.isBlockedByUser1());
        assertFalse(conv.isBlockedByUser2());
        assertEquals(5, conv.getMessageCount());
        assertEquals(2, conv.getUnreadCountUser1());
        assertEquals(0, conv.getUnreadCountUser2());
        assertEquals("t1", conv.getTenantId());
    }

    @Test
    @DisplayName("Metadata should support complex nested structures")
    void shouldAcceptComplexMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("theme", "dark");
        metadata.put("pinned", true);
        metadata.put("tags", new String[]{"work", "important"});

        ConversationData conv = new ConversationData();
        conv.setMetadata(metadata);

        assertEquals("dark", conv.getMetadata().get("theme"));
        assertEquals(true, conv.getMetadata().get("pinned"));
    }

    @Test
    @DisplayName("ToString should include identifying info")
    void shouldHaveMeaningfulToString() {
        ConversationData conv = ConversationData.builder()
                .id("conv-42").user1Id("alice").user2Id("bob").build();

        String str = conv.toString();
        assertNotNull(str);
        assertTrue(str.contains("conv-42") || str.contains("ConversationData"),
                "toString should contain identifying info");
    }
}
