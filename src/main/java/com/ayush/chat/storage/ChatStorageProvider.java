package com.ayush.chat.storage;

import com.ayush.chat.storage.dto.ChatMessageData;
import com.ayush.chat.storage.dto.ConversationData;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Unified interface for chat storage operations.
 * 
 * All storage backends (MySQL, PostgreSQL, MongoDB, Firebase, Supabase)
 * implement this interface, enabling seamless database switching.
 * 
 * Switch the backend by changing: chat.storage.type in application.yml
 * 
 * <h3>Usage Example:</h3>
 * <pre>
 * &#64;Service
 * public class ChatService {
 *     private final ChatStorageProvider storage;
 *     
 *     public ChatService(ChatStorageProvider storage) {
 *         this.storage = storage;
 *     }
 *     
 *     public void send(ChatMessageData msg) {
 *         storage.saveMessage(msg);  // Works with any backend!
 *     }
 * }
 * </pre>
 */
public interface ChatStorageProvider {

    // ========== Message Operations ==========

    /**
     * Save a new message to storage.
     * @param message the message data to save
     * @return the saved message with generated ID and timestamps
     */
    ChatMessageData saveMessage(ChatMessageData message);

    /**
     * Save multiple messages in a batch (bulk insert).
     * @param messages the messages to save
     * @return the saved messages
     */
    List<ChatMessageData> saveMessages(List<ChatMessageData> messages);

    /**
     * Find a message by its ID.
     * @param messageId the message ID
     * @return the message if found
     */
    Optional<ChatMessageData> findMessageById(String messageId);

    /**
     * Find all messages in a conversation, ordered by creation time ascending.
     * @param conversationId the conversation ID
     * @return list of messages
     */
    List<ChatMessageData> findMessagesByConversation(String conversationId);

    /**
     * Find messages in a conversation with pagination.
     * @param conversationId the conversation ID
     * @param offset the offset (skip this many messages)
     * @param limit the maximum number of messages to return
     * @return list of messages
     */
    List<ChatMessageData> findMessagesByConversation(String conversationId, int offset, int limit);

    /**
     * Find messages in a conversation created after a specific timestamp.
     * @param conversationId the conversation ID
     * @param since only messages created after this timestamp
     * @return list of messages
     */
    List<ChatMessageData> findMessagesSince(String conversationId, Instant since);

    /**
     * Find all unread messages for a specific user.
     * @param userId the user ID
     * @return list of unread messages
     */
    List<ChatMessageData> findUnreadMessages(String userId);

    /**
     * Find unread messages from a specific sender to a specific receiver.
     * @param senderId the sender's user ID
     * @param receiverId the receiver's user ID
     * @return list of unread messages
     */
    List<ChatMessageData> findUnreadMessages(String senderId, String receiverId);

    /**
     * Mark specific messages as read.
     * @param messageIds the IDs of messages to mark as read
     */
    void markMessagesAsRead(List<String> messageIds);

    /**
     * Update the status of a message.
     * @param messageId the message ID
     * @param status the new status (SENT, DELIVERED, READ, FAILED)
     */
    void updateMessageStatus(String messageId, String status);

    /**
     * Soft delete a message.
     * @param messageId the message ID
     */
    void deleteMessage(String messageId);

    // ========== Conversation Operations ==========

    /**
     * Save a new conversation to storage.
     * @param conversation the conversation data to save
     * @return the saved conversation with generated ID and timestamps
     */
    ConversationData saveConversation(ConversationData conversation);

    /**
     * Find a conversation by its ID.
     * @param conversationId the conversation ID
     * @return the conversation if found
     */
    Optional<ConversationData> findConversationById(String conversationId);

    /**
     * Find a conversation between two users.
     * @param user1Id first user ID
     * @param user2Id second user ID
     * @return the conversation if it exists
     */
    Optional<ConversationData> findConversationByUsers(String user1Id, String user2Id);

    /**
     * Find all conversations for a specific user.
     * @param userId the user ID
     * @return list of conversations ordered by last message time (most recent first)
     */
    List<ConversationData> findConversationsByUser(String userId);

    /**
     * Update the last message preview of a conversation.
     * @param conversationId the conversation ID
     * @param lastMessage the message preview text
     * @param lastMessageTime the timestamp of the last message
     */
    void updateConversationLastMessage(String conversationId, String lastMessage, Instant lastMessageTime);

    /**
     * Update the status of a conversation.
     * @param conversationId the conversation ID
     * @param status the new status (INIT, ACTIVE, INACTIVE)
     */
    void updateConversationStatus(String conversationId, String status);

    /**
     * Set a block flag on a conversation.
     * @param conversationId the conversation ID
     * @param userId the user who is blocking
     * @param blocked true to block, false to unblock
     */
    void setConversationBlock(String conversationId, String userId, boolean blocked);

    /**
     * Soft delete a conversation.
     * @param conversationId the conversation ID
     */
    void deleteConversation(String conversationId);

    // ========== Provider Metadata ==========

    /**
     * Get the type of storage this provider uses.
     */
    StorageType getStorageType();

    /**
     * Check if the storage backend is available/connected.
     * @return true if healthy
     */
    boolean isHealthy();

    /**
     * Get a description of this provider for diagnostics.
     */
    default String getDescription() {
        return getStorageType().getDisplayName() + " Chat Storage Provider";
    }
}
