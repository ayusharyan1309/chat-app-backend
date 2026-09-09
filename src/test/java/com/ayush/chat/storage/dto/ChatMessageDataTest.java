package com.ayush.chat.storage.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ChatMessageData DTO.
 * Validates builder pattern, default values, Lombok-generated methods,
 * and all field setters/getters.
 */
@DisplayName("ChatMessageData DTO Tests")
class ChatMessageDataTest {

    @Test
    @DisplayName("Should create message with builder pattern")
    void shouldCreateWithBuilder() {
        ChatMessageData msg = ChatMessageData.builder()
                .id("msg-1")
                .conversationId("conv-1")
                .senderId("user-1")
                .senderEmail("user1@test.com")
                .senderName("User 1")
                .receiverId("user-2")
                .receiverEmail("user2@test.com")
                .content("Hello!")
                .messageType("TEXT")
                .mediaUrl("https://example.com/img.png")
                .mediaMimeType("image/png")
                .mediaSize(1024L)
                .read(false)
                .status("SENT")
                .deleted(false)
                .tenantId("tenant-1")
                .build();

        assertEquals("msg-1", msg.getId());
        assertEquals("conv-1", msg.getConversationId());
        assertEquals("user-1", msg.getSenderId());
        assertEquals("user1@test.com", msg.getSenderEmail());
        assertEquals("User 1", msg.getSenderName());
        assertEquals("user-2", msg.getReceiverId());
        assertEquals("user2@test.com", msg.getReceiverEmail());
        assertEquals("Hello!", msg.getContent());
        assertEquals("TEXT", msg.getMessageType());
        assertEquals("https://example.com/img.png", msg.getMediaUrl());
        assertEquals("image/png", msg.getMediaMimeType());
        assertEquals(1024L, msg.getMediaSize());
        assertFalse(msg.isRead());
        assertEquals("SENT", msg.getStatus());
        assertFalse(msg.isDeleted());
        assertEquals("tenant-1", msg.getTenantId());
    }

    @Test
    @DisplayName("Default values should be correct")
    void shouldHaveCorrectDefaults() {
        ChatMessageData msg = new ChatMessageData();

        assertNull(msg.getId());
        assertNull(msg.getConversationId());
        assertNull(msg.getSenderId());
        assertNull(msg.getContent());
        assertEquals("TEXT", msg.getMessageType(), "Default message type should be TEXT");
        assertFalse(msg.isRead(), "Default read should be false");
        assertEquals("SENT", msg.getStatus(), "Default status should be SENT");
        assertFalse(msg.isDeleted(), "Default deleted should be false");
        assertNull(msg.getMetadata());
        assertNull(msg.getTenantId());
    }

    @Test
    @DisplayName("Should support setters and getters")
    void shouldSupportSettersAndGetters() {
        ChatMessageData msg = new ChatMessageData();
        Instant now = Instant.now();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key", "value");

        msg.setId("test-id");
        msg.setConversationId("conv-id");
        msg.setSenderId("sender");
        msg.setSenderEmail("sender@test.com");
        msg.setSenderName("Sender Name");
        msg.setReceiverId("receiver");
        msg.setReceiverEmail("receiver@test.com");
        msg.setContent("Test content");
        msg.setMessageType("IMAGE");
        msg.setMediaUrl("http://media.test.com");
        msg.setMediaMimeType("image/jpeg");
        msg.setMediaSize(2048L);
        msg.setRead(true);
        msg.setReadAt(now);
        msg.setStatus("DELIVERED");
        msg.setDeleted(true);
        msg.setCreatedAt(now);
        msg.setUpdatedAt(now);
        msg.setMetadata(metadata);
        msg.setTenantId("tenant-42");

        assertEquals("test-id", msg.getId());
        assertEquals("conv-id", msg.getConversationId());
        assertEquals("sender", msg.getSenderId());
        assertEquals("sender@test.com", msg.getSenderEmail());
        assertEquals("Sender Name", msg.getSenderName());
        assertEquals("receiver", msg.getReceiverId());
        assertEquals("receiver@test.com", msg.getReceiverEmail());
        assertEquals("Test content", msg.getContent());
        assertEquals("IMAGE", msg.getMessageType());
        assertEquals("http://media.test.com", msg.getMediaUrl());
        assertEquals("image/jpeg", msg.getMediaMimeType());
        assertEquals(2048L, msg.getMediaSize());
        assertTrue(msg.isRead());
        assertEquals(now, msg.getReadAt());
        assertEquals("DELIVERED", msg.getStatus());
        assertTrue(msg.isDeleted());
        assertEquals(now, msg.getCreatedAt());
        assertEquals(now, msg.getUpdatedAt());
        assertEquals(metadata, msg.getMetadata());
        assertEquals("tenant-42", msg.getTenantId());
    }

    @Test
    @DisplayName("Equals and hashCode should work")
    void shouldSupportEqualsAndHashCode() {
        Instant now = Instant.now();
        ChatMessageData msg1 = ChatMessageData.builder()
                .id("id-1").content("hello").createdAt(now).build();
        ChatMessageData msg2 = ChatMessageData.builder()
                .id("id-1").content("hello").createdAt(now).build();

        assertEquals(msg1, msg2, "Messages with same data should be equal");
        assertEquals(msg1.hashCode(), msg2.hashCode());
    }

    @Test
    @DisplayName("Not-equal messages should not be equal")
    void shouldNotBeEqualForDifferentData() {
        ChatMessageData msg1 = ChatMessageData.builder().id("id-1").content("hello").build();
        ChatMessageData msg2 = ChatMessageData.builder().id("id-2").content("hello").build();

        assertNotEquals(msg1, msg2);
    }

    @Test
    @DisplayName("ToString should include key fields")
    void shouldHaveMeaningfulToString() {
        ChatMessageData msg = ChatMessageData.builder()
                .id("msg-123").content("test").build();

        String str = msg.toString();
        assertNotNull(str);
        assertTrue(str.contains("msg-123") || str.contains("ChatMessageData"),
                "toString should contain identifying info");
    }

    @Test
    @DisplayName("Media size should accept null")
    void shouldAcceptNullMediaSize() {
        ChatMessageData msg = new ChatMessageData();
        msg.setMediaSize(null);
        assertNull(msg.getMediaSize());
    }

    @Test
    @DisplayName("Metadata should accept complex nested structures")
    void shouldAcceptComplexMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("reactions", Map.of("👍", 5, "❤️", 3));
        metadata.put("thread", Map.of("id", "thread-1", "participants", new String[]{"u1", "u2"}));

        ChatMessageData msg = new ChatMessageData();
        msg.setMetadata(metadata);

        assertEquals(5, ((Map<?, ?>) msg.getMetadata().get("reactions")).get("👍"));
    }

    @Test
    @DisplayName("No-arg constructor should create empty message")
    void noArgConstructorShouldCreateEmptyMessage() {
        ChatMessageData msg = new ChatMessageData();
        assertNull(msg.getId());
        assertNull(msg.getContent());
        assertEquals("TEXT", msg.getMessageType());
    }

    @Test
    @DisplayName("All-arg constructor should set all fields")
    void allArgConstructorShouldSetAllFields() {
        Instant now = Instant.now();
        ChatMessageData msg = new ChatMessageData(
                "id", "conv", "sender", "s@test.com", "S",
                "receiver", "r@test.com", "content",
                "FILE", "http://url", "application/pdf", 512L,
                true, now, "READ", false, now, now, null, "t1"
        );

        assertEquals("id", msg.getId());
        assertEquals("conv", msg.getConversationId());
        assertEquals("sender", msg.getSenderId());
        assertEquals("FILE", msg.getMessageType());
        assertTrue(msg.isRead());
        assertEquals("READ", msg.getStatus());
    }
}
