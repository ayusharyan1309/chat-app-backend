package com.ayush.chat.storage.provider;

import com.ayush.chat.storage.StorageType;
import com.ayush.chat.storage.config.ChatStorageProperties;
import com.ayush.chat.storage.dto.ChatMessageData;
import com.ayush.chat.storage.dto.ConversationData;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SqlChatStorageProvider using H2 in-memory database.
 * Tests all CRUD operations for messages and conversations,
 * pagination, search, and status management.
 */
@DisplayName("SqlChatStorageProvider (H2) Integration Tests")
class SqlChatStorageProviderTest {

    private static SqlChatStorageProvider provider;
    private static String uniqueSuffix;

    @BeforeAll
    static void setUpProvider() {
        uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);

        ChatStorageProperties properties = new ChatStorageProperties();
        properties.setType(StorageType.H2);
        properties.setCollectionPrefix("test_" + uniqueSuffix + "_");
        properties.setEnableAudit(true);

        ChatStorageProperties.SqlConfig sql = properties.getSql();
        sql.setUrl("jdbc:h2:mem:test_sql_" + uniqueSuffix + ";DB_CLOSE_DELAY=-1");
        sql.setUsername("sa");
        sql.setPassword("");
        sql.setDriverClassName("org.h2.Driver");

        provider = new SqlChatStorageProvider(properties, StorageType.H2);
    }

    // ========== Health Check ==========

    @Test
    @DisplayName("Provider should be healthy")
    void shouldReportHealthyStatus() {
        assertTrue(provider.isHealthy(), "H2 provider should be healthy");
    }

    @Test
    @DisplayName("Provider should report correct storage type")
    void shouldReportCorrectStorageType() {
        assertEquals(StorageType.H2, provider.getStorageType());
    }

    // ========== Message Operations ==========

    @Test
    @DisplayName("Save and retrieve a message by ID")
    void shouldSaveAndFindMessageById() {
        ChatMessageData message = createTestMessage("conv1", "user1", "user2", "Hello World");

        ChatMessageData saved = provider.saveMessage(message);

        assertNotNull(saved.getId(), "Message should have an ID");
        assertNotNull(saved.getCreatedAt(), "Message should have createdAt");
        assertNotNull(saved.getUpdatedAt(), "Message should have updatedAt");
        assertEquals("SENT", saved.getStatus(), "Default status should be SENT");

        Optional<ChatMessageData> found = provider.findMessageById(saved.getId());
        assertTrue(found.isPresent(), "Message should be found by ID");
        assertEquals("Hello World", found.get().getContent());
        assertEquals("user1", found.get().getSenderId());
        assertEquals("user2", found.get().getReceiverId());
    }

    @Test
    @DisplayName("Save message with auto-generated ID when null")
    void shouldAutoGenerateIdWhenNull() {
        ChatMessageData message = createTestMessage("conv1", "user1", "user2", "Auto ID");
        message.setId(null);

        ChatMessageData saved = provider.saveMessage(message);

        assertNotNull(saved.getId(), "ID should be auto-generated");
        assertFalse(saved.getId().isEmpty(), "ID should not be empty");
    }

    @Test
    @DisplayName("Save message with auto-generated timestamp when null")
    void shouldAutoGenerateTimestampsWhenNull() {
        ChatMessageData message = createTestMessage("conv1", "user1", "user2", "Timestamps");
        message.setCreatedAt(null);

        ChatMessageData saved = provider.saveMessage(message);

        assertNotNull(saved.getCreatedAt(), "createdAt should be auto-generated");
        assertNotNull(saved.getUpdatedAt(), "updatedAt should be auto-generated");
    }

    @Test
    @DisplayName("Save message with existing ID should upsert")
    void shouldUpsertOnExistingId() {
        ChatMessageData message = createTestMessage("conv1", "user1", "user2", "Original");
        ChatMessageData saved = provider.saveMessage(message);

        // Update the message content and save again
        saved.setContent("Updated content");
        provider.saveMessage(saved);

        Optional<ChatMessageData> found = provider.findMessageById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("Updated content", found.get().getContent(),
                "Message should be updated on duplicate ID");
    }

    @Test
    @DisplayName("Find messages by conversation")
    void shouldFindMessagesByConversation() {
        String convId = "conv_find_test_" + uniqueSuffix;
        provider.saveMessage(createTestMessage(convId, "u1", "u2", "Msg 1"));
        provider.saveMessage(createTestMessage(convId, "u1", "u2", "Msg 2"));
        provider.saveMessage(createTestMessage(convId, "u2", "u1", "Msg 3"));

        List<ChatMessageData> messages = provider.findMessagesByConversation(convId);

        assertEquals(3, messages.size(), "Should find all 3 messages in conversation");
        // Should be ordered by created_at ASC
        assertEquals("Msg 1", messages.get(0).getContent());
        assertEquals("Msg 2", messages.get(1).getContent());
        assertEquals("Msg 3", messages.get(2).getContent());
    }

    @Test
    @DisplayName("Find messages by conversation with pagination")
    void shouldFindMessagesByConversationWithPagination() {
        String convId = "conv_pagination_" + uniqueSuffix;
        for (int i = 0; i < 10; i++) {
            ChatMessageData msg = createTestMessage(convId, "u1", "u2", "Msg " + i);
            provider.saveMessage(msg);
            // Small delay to ensure distinct timestamps for reliable ordering
            try { Thread.sleep(50); } catch (InterruptedException e) { /* ignored */ }
        }

        List<ChatMessageData> firstPage = provider.findMessagesByConversation(convId, 0, 3);
        assertEquals(3, firstPage.size(), "First page should have 3 messages");

        List<ChatMessageData> secondPage = provider.findMessagesByConversation(convId, 3, 3);
        assertEquals(3, secondPage.size(), "Second page should have 3 messages");

        List<ChatMessageData> lastPage = provider.findMessagesByConversation(convId, 9, 3);
        assertEquals(1, lastPage.size(), "Last page should have 1 message");

        // Verify no overlap between first and last page
        Set<String> firstPageIds = firstPage.stream().map(ChatMessageData::getId).collect(java.util.stream.Collectors.toSet());
        Set<String> lastPageIds = lastPage.stream().map(ChatMessageData::getId).collect(java.util.stream.Collectors.toSet());
        assertTrue(Collections.disjoint(firstPageIds, lastPageIds),
                "First and last page should not have overlapping message IDs");

        // Verify total count across all pages
        int totalMessages = 0;
        int offset = 0;
        while (true) {
            List<ChatMessageData> page = provider.findMessagesByConversation(convId, offset, 3);
            if (page.isEmpty()) break;
            totalMessages += page.size();
            offset += page.size();
            if (offset > 15) break; // safety valve
        }
        assertEquals(10, totalMessages, "Total messages across all pages should be 10");
    }

    @Test
    @DisplayName("Find messages since a specific timestamp")
    void shouldFindMessagesSinceTimestamp() {
        String convId = "conv_since_" + uniqueSuffix;
        Instant before = Instant.now();
        provider.saveMessage(createTestMessage(convId, "u1", "u2", "Before"));
        
        // Small delay to ensure different timestamp
        try { Thread.sleep(50); } catch (InterruptedException e) { /* ignored */ }
        Instant cutoff = Instant.now();
        try { Thread.sleep(50); } catch (InterruptedException e) { /* ignored */ }

        provider.saveMessage(createTestMessage(convId, "u1", "u2", "After"));

        List<ChatMessageData> messages = provider.findMessagesSince(convId, cutoff);

        assertEquals(1, messages.size(), "Should find only messages after cutoff");
        assertEquals("After", messages.get(0).getContent());
    }

    @Test
    @DisplayName("Find unread messages for a user")
    void shouldFindUnreadMessages() {
        String convId = "conv_unread_" + uniqueSuffix;
        ChatMessageData unread1 = createTestMessage(convId, "sender", "receiver_unread", "Unread 1");
        unread1.setRead(false);
        provider.saveMessage(unread1);

        ChatMessageData unread2 = createTestMessage(convId, "sender", "receiver_unread", "Unread 2");
        unread2.setRead(false);
        provider.saveMessage(unread2);

        ChatMessageData readMsg = createTestMessage(convId, "sender", "receiver_unread", "Read msg");
        readMsg.setRead(true);
        provider.saveMessage(readMsg);

        List<ChatMessageData> unread = provider.findUnreadMessages("receiver_unread");

        assertEquals(2, unread.size(), "Should find 2 unread messages");
        assertTrue(unread.stream().allMatch(m -> !m.isRead()), "All returned messages should be unread");
    }

    @Test
    @DisplayName("Find unread messages from specific sender to receiver")
    void shouldFindUnreadMessagesFromSenderToReceiver() {
        String convId = "conv_unread_pair_" + uniqueSuffix;
        provider.saveMessage(createTestMessage(convId, "specific_sender", "specific_receiver", "Unread from sender"));
        provider.saveMessage(createTestMessage(convId, "other_sender", "specific_receiver", "Unread from other"));

        List<ChatMessageData> unread = provider.findUnreadMessages("specific_sender", "specific_receiver");

        assertEquals(1, unread.size(), "Should find 1 unread from specific sender");
        assertEquals("specific_sender", unread.get(0).getSenderId());
    }

    @Test
    @DisplayName("Mark messages as read")
    void shouldMarkMessagesAsRead() {
        String convId = "conv_mark_read_" + uniqueSuffix;
        ChatMessageData msg = createTestMessage(convId, "u1", "u2", "Mark me");
        msg.setRead(false);
        ChatMessageData saved = provider.saveMessage(msg);

        provider.markMessagesAsRead(List.of(saved.getId()));

        Optional<ChatMessageData> found = provider.findMessageById(saved.getId());
        assertTrue(found.isPresent());
        assertTrue(found.get().isRead(), "Message should be marked as read");
        assertNotNull(found.get().getReadAt(), "readAt should be set");
    }

    @Test
    @DisplayName("Update message status")
    void shouldUpdateMessageStatus() {
        String convId = "conv_status_" + uniqueSuffix;
        ChatMessageData msg = createTestMessage(convId, "u1", "u2", "Status test");
        ChatMessageData saved = provider.saveMessage(msg);
        assertEquals("SENT", saved.getStatus());

        provider.updateMessageStatus(saved.getId(), "DELIVERED");

        Optional<ChatMessageData> found = provider.findMessageById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("DELIVERED", found.get().getStatus());
    }

    @Test
    @DisplayName("Soft delete a message")
    void shouldSoftDeleteMessage() {
        String convId = "conv_delete_" + uniqueSuffix;
        ChatMessageData msg = createTestMessage(convId, "u1", "u2", "Delete me");
        ChatMessageData saved = provider.saveMessage(msg);

        provider.deleteMessage(saved.getId());

        Optional<ChatMessageData> found = provider.findMessageById(saved.getId());
        assertFalse(found.isPresent(), "Deleted message should not be found");
    }

    @Test
    @DisplayName("Batch save messages")
    void shouldBatchSaveMessages() {
        String convId = "conv_batch_" + uniqueSuffix;
        List<ChatMessageData> messages = List.of(
                createTestMessage(convId, "u1", "u2", "Batch 1"),
                createTestMessage(convId, "u1", "u2", "Batch 2"),
                createTestMessage(convId, "u1", "u2", "Batch 3")
        );

        List<ChatMessageData> saved = provider.saveMessages(messages);

        assertEquals(3, saved.size(), "Should save all 3 messages");
        saved.forEach(m -> assertNotNull(m.getId(), "Each message should have an ID"));

        List<ChatMessageData> found = provider.findMessagesByConversation(convId);
        assertEquals(3, found.size(), "Should find all batch-saved messages");
    }

    // ========== Conversation Operations ==========

    @Test
    @DisplayName("Save and retrieve a conversation by ID")
    void shouldSaveAndFindConversationById() {
        ConversationData conv = createTestConversation("user_a", "user_b");

        ConversationData saved = provider.saveConversation(conv);

        assertNotNull(saved.getId(), "Conversation should have an ID");
        assertNotNull(saved.getCreatedAt(), "Conversation should have createdAt");

        Optional<ConversationData> found = provider.findConversationById(saved.getId());
        assertTrue(found.isPresent(), "Conversation should be found by ID");
        assertEquals("user_a", found.get().getUser1Id());
        assertEquals("user_b", found.get().getUser2Id());
    }

    @Test
    @DisplayName("Find conversation by user pair")
    void shouldFindConversationByUsers() {
        ConversationData conv = createTestConversation("find_u1", "find_u2");
        provider.saveConversation(conv);

        // Forward order
        Optional<ConversationData> found1 = provider.findConversationByUsers("find_u1", "find_u2");
        assertTrue(found1.isPresent(), "Should find conversation with forward user order");

        // Reverse order
        Optional<ConversationData> found2 = provider.findConversationByUsers("find_u2", "find_u1");
        assertTrue(found2.isPresent(), "Should find conversation with reverse user order");
        assertEquals(found1.get().getId(), found2.get().getId(),
                "Both orders should return the same conversation");
    }

    @Test
    @DisplayName("Find all conversations for a user")
    void shouldFindConversationsByUser() {
        provider.saveConversation(createTestConversation("multi_u", "other1"));
        provider.saveConversation(createTestConversation("multi_u", "other2"));
        provider.saveConversation(createTestConversation("other3", "other4")); // Not involving multi_u

        List<ConversationData> conversations = provider.findConversationsByUser("multi_u");

        assertEquals(2, conversations.size(), "Should find 2 conversations for multi_u");
        assertTrue(conversations.stream().allMatch(c ->
                c.getUser1Id().equals("multi_u") || c.getUser2Id().equals("multi_u")),
                "All conversations should involve multi_u");
    }

    @Test
    @DisplayName("Update conversation last message")
    void shouldUpdateConversationLastMessage() {
        ConversationData conv = createTestConversation("u1", "u2");
        ConversationData saved = provider.saveConversation(conv);

        Instant newTime = Instant.now();
        provider.updateConversationLastMessage(saved.getId(), "Hello there!", newTime);

        Optional<ConversationData> found = provider.findConversationById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("Hello there!", found.get().getLastMessage());
        assertEquals(1, found.get().getMessageCount(), "Message count should be incremented");
    }

    @Test
    @DisplayName("Update conversation status")
    void shouldUpdateConversationStatus() {
        ConversationData conv = createTestConversation("u1_status", "u2_status");
        ConversationData saved = provider.saveConversation(conv);
        assertEquals("INIT", saved.getStatus());

        provider.updateConversationStatus(saved.getId(), "ACTIVE");

        Optional<ConversationData> found = provider.findConversationById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("ACTIVE", found.get().getStatus());
    }

    @Test
    @DisplayName("Block and unblock conversation")
    void shouldBlockAndUnblockConversation() {
        ConversationData conv = createTestConversation("block_u1", "block_u2");
        ConversationData saved = provider.saveConversation(conv);

        // Block
        provider.setConversationBlock(saved.getId(), "block_u1", true);

        Optional<ConversationData> found = provider.findConversationById(saved.getId());
        assertTrue(found.isPresent());
        assertTrue(found.get().isBlockedByUser1(), "User1 should be blocking");
        assertEquals("INACTIVE", found.get().getStatus(), "Status should be INACTIVE when blocked");

        // Unblock
        provider.setConversationBlock(saved.getId(), "block_u1", false);

        Optional<ConversationData> foundAfterUnblock = provider.findConversationById(saved.getId());
        assertTrue(foundAfterUnblock.isPresent());
        assertFalse(foundAfterUnblock.get().isBlockedByUser1(), "User1 should not be blocking");
    }

    @Test
    @DisplayName("Soft delete a conversation")
    void shouldSoftDeleteConversation() {
        ConversationData conv = createTestConversation("del_u1", "del_u2");
        ConversationData saved = provider.saveConversation(conv);

        provider.deleteConversation(saved.getId());

        Optional<ConversationData> found = provider.findConversationById(saved.getId());
        assertTrue(found.isPresent(), "Conversation should still exist (soft delete)");
        assertEquals("INACTIVE", found.get().getStatus(),
                "Deleted conversation should have INACTIVE status");
    }

    // ========== Edge Cases ==========

    @Test
    @DisplayName("Find non-existent message should return empty")
    void shouldReturnEmptyForNonExistentMessage() {
        Optional<ChatMessageData> found = provider.findMessageById("non-existent-id");
        assertFalse(found.isPresent());
    }

    @Test
    @DisplayName("Find non-existent conversation should return empty")
    void shouldReturnEmptyForNonExistentConversation() {
        Optional<ConversationData> found = provider.findConversationById("non-existent-id");
        assertFalse(found.isPresent());
    }

    @Test
    @DisplayName("Mark empty list of messages as read should not throw")
    void shouldNotThrowWhenMarkingEmptyListAsRead() {
        assertDoesNotThrow(() -> provider.markMessagesAsRead(List.of()));
        assertDoesNotThrow(() -> provider.markMessagesAsRead(null));
    }

    @Test
    @DisplayName("Find messages for non-existent conversation should return empty list")
    void shouldReturnEmptyListForNonExistentConversation() {
        List<ChatMessageData> messages = provider.findMessagesByConversation("non-existent");
        assertTrue(messages.isEmpty());
    }

    @Test
    @DisplayName("Find conversations for non-existent user should return empty list")
    void shouldReturnEmptyListForNonExistentUser() {
        List<ConversationData> conversations = provider.findConversationsByUser("non-existent");
        assertTrue(conversations.isEmpty());
    }

    @Test
    @DisplayName("Save message with metadata")
    void shouldSaveMessageWithMetadata() {
        ChatMessageData msg = createTestMessage("meta_conv", "u1", "u2", "With metadata");
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("threadId", "thread-123");
        metadata.put("reactions", List.of("👍", "❤️"));
        msg.setMetadata(metadata);

        ChatMessageData saved = provider.saveMessage(msg);

        Optional<ChatMessageData> found = provider.findMessageById(saved.getId());
        assertTrue(found.isPresent());
        // Metadata is stored as JSON string in SQL, so it comes back as a string representation
        assertNotNull(found.get().getMetadata(), "Metadata should be preserved (as JSON string in SQL)");
    }

    @Test
    @DisplayName("Save message with all fields populated")
    void shouldSaveMessageWithAllFields() {
        ChatMessageData msg = ChatMessageData.builder()
                .conversationId("full_conv")
                .senderId("sender_full")
                .senderEmail("sender@example.com")
                .senderName("Full Sender")
                .receiverId("receiver_full")
                .receiverEmail("receiver@example.com")
                .content("Full message content")
                .messageType("IMAGE")
                .mediaUrl("https://example.com/image.png")
                .mediaMimeType("image/png")
                .mediaSize(1024L)
                .read(false)
                .status("SENT")
                .deleted(false)
                .tenantId("tenant_1")
                .build();

        ChatMessageData saved = provider.saveMessage(msg);

        Optional<ChatMessageData> found = provider.findMessageById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("IMAGE", found.get().getMessageType());
        assertEquals("https://example.com/image.png", found.get().getMediaUrl());
        assertEquals("image/png", found.get().getMediaMimeType());
        assertEquals(1024L, found.get().getMediaSize());
        assertEquals("tenant_1", found.get().getTenantId());
    }

    @Test
    @DisplayName("Save conversation with all fields populated")
    void shouldSaveConversationWithAllFields() {
        ConversationData conv = ConversationData.builder()
                .user1Id("u1_full")
                .user1Email("u1@test.com")
                .user2Id("u2_full")
                .user2Email("u2@test.com")
                .lastMessage("Test last message")
                .lastMessageTime(Instant.now())
                .status("ACTIVE")
                .blockedByUser1(false)
                .blockedByUser2(true)
                .messageCount(42)
                .unreadCountUser1(3)
                .unreadCountUser2(0)
                .tenantId("tenant_1")
                .build();

        ConversationData saved = provider.saveConversation(conv);

        Optional<ConversationData> found = provider.findConversationById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("u1@test.com", found.get().getUser1Email());
        assertEquals("u2@test.com", found.get().getUser2Email());
        assertTrue(found.get().isBlockedByUser2());
        assertEquals("tenant_1", found.get().getTenantId());
    }

    // ========== Helper Methods ==========

    private ChatMessageData createTestMessage(String conversationId, String senderId, String receiverId, String content) {
        return ChatMessageData.builder()
                .conversationId(conversationId)
                .senderId(senderId)
                .senderEmail(senderId + "@test.com")
                .senderName(senderId + " Name")
                .receiverId(receiverId)
                .receiverEmail(receiverId + "@test.com")
                .content(content)
                .messageType("TEXT")
                .build();
    }

    private ConversationData createTestConversation(String user1Id, String user2Id) {
        return ConversationData.builder()
                .user1Id(user1Id)
                .user1Email(user1Id + "@test.com")
                .user2Id(user2Id)
                .user2Email(user2Id + "@test.com")
                .status("INIT")
                .build();
    }
}
