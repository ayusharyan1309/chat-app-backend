package com.ayush.chat.storage.provider;

import com.ayush.chat.storage.ChatStorageProvider;
import com.ayush.chat.storage.StorageType;
import com.ayush.chat.storage.config.ChatStorageProperties;
import com.ayush.chat.storage.dto.ChatMessageData;
import com.ayush.chat.storage.dto.ConversationData;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Abstract base class for chat storage providers.
 * 
 * Provides common utility methods and template pattern for all storage backends.
 * Concrete implementations only need to implement the database-specific operations.
 */
@Slf4j
public abstract class AbstractChatStorageProvider implements ChatStorageProvider {

    protected final ChatStorageProperties properties;

    protected AbstractChatStorageProvider(ChatStorageProperties properties) {
        this.properties = properties;
    }

    // ========== Template Methods ==========

    @Override
    public ChatMessageData saveMessage(ChatMessageData message) {
        // Pre-processing: ensure required fields
        if (message.getId() == null || message.getId().isEmpty()) {
            message.setId(generateId());
        }
        if (message.getCreatedAt() == null) {
            message.setCreatedAt(Instant.now());
        }
        message.setUpdatedAt(Instant.now());
        if (message.getStatus() == null) {
            message.setStatus("SENT");
        }

        log.debug("[{}] Saving message: conversation={}, sender={}", 
                getStorageType(), message.getConversationId(), message.getSenderId());

        // Delegate to concrete implementation
        ChatMessageData saved = doSaveMessage(message);

        if (properties.isEnableAudit()) {
            log.info("[{}] Message saved: id={}, conversation={}", 
                    getStorageType(), saved.getId(), saved.getConversationId());
        }

        return saved;
    }

    @Override
    public ConversationData saveConversation(ConversationData conversation) {
        if (conversation.getId() == null || conversation.getId().isEmpty()) {
            conversation.setId(generateId());
        }
        if (conversation.getCreatedAt() == null) {
            conversation.setCreatedAt(Instant.now());
        }
        conversation.setUpdatedAt(Instant.now());

        log.debug("[{}] Saving conversation: user1={}, user2={}", 
                getStorageType(), conversation.getUser1Id(), conversation.getUser2Id());

        ConversationData saved = doSaveConversation(conversation);

        if (properties.isEnableAudit()) {
            log.info("[{}] Conversation saved: id={}, users={}/{}", 
                    getStorageType(), saved.getId(), saved.getUser1Id(), saved.getUser2Id());
        }

        return saved;
    }

    @Override
    public void markMessagesAsRead(List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) return;
        
        log.debug("[{}] Marking {} messages as read", getStorageType(), messageIds.size());
        doMarkMessagesAsRead(messageIds);
    }

    @Override
    public void deleteMessage(String messageId) {
        log.debug("[{}] Soft deleting message: {}", getStorageType(), messageId);
        doDeleteMessage(messageId);
    }

    @Override
    public void deleteConversation(String conversationId) {
        log.debug("[{}] Soft deleting conversation: {}", getStorageType(), conversationId);
        doDeleteConversation(conversationId);
    }

    // ========== Abstract Methods (must be implemented by subclasses) ==========

    protected abstract ChatMessageData doSaveMessage(ChatMessageData message);

    protected abstract ConversationData doSaveConversation(ConversationData conversation);

    protected abstract void doMarkMessagesAsRead(List<String> messageIds);

    protected abstract void doDeleteMessage(String messageId);

    protected abstract void doDeleteConversation(String conversationId);

    // ========== Utility Methods ==========

    /**
     * Generate a unique ID for entities.
     */
    protected String generateId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Prepend the configured collection prefix to a table/collection name.
     */
    protected String prefixed(String name) {
        return properties.getCollectionPrefix() + name;
    }
}
