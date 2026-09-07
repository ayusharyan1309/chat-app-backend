package com.ayush.chat.storage.provider;

import com.ayush.chat.storage.StorageType;
import com.ayush.chat.storage.config.ChatStorageProperties;
import com.ayush.chat.storage.dto.ChatMessageData;
import com.ayush.chat.storage.dto.ConversationData;
import com.google.api.core.ApiFuture;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Firebase Firestore chat storage provider.
 * 
 * Uses Google Cloud Firestore as the backend for chat storage.
 * Firestore is a NoSQL document database that scales automatically.
 * 
 * Collections:
 * - chat_messages: Document collection for messages
 * - chat_conversations: Document collection for conversations
 * 
 * <p>Setup requirements:</p>
 * <ul>
 *   <li>Google Cloud project with Firestore enabled</li>
 *   <li>Service account JSON file path</li>
 *   <li>Firebase Admin SDK dependency in pom.xml</li>
 * </ul>
 */
@Slf4j
public class FirebaseChatStorageProvider extends AbstractChatStorageProvider {

    private Firestore firestore;
    private final ChatStorageProperties properties;

    public FirebaseChatStorageProvider(ChatStorageProperties properties) {
        super(properties);
        this.properties = properties;
        initializeFirebase();
    }

    @Override
    public StorageType getStorageType() {
        return StorageType.FIREBASE;
    }

    @Override
    public boolean isHealthy() {
        try {
            // Simple health check by trying to access Firestore
            firestore.collection(prefixed("health_check")).document("ping").get().get();
            return true;
        } catch (Exception e) {
            log.error("[Firebase] Health check failed", e);
            return false;
        }
    }

    // ========== Message Operations ==========

    @Override
    protected ChatMessageData doSaveMessage(ChatMessageData message) {
        DocumentReference docRef = firestore.collection(prefixed("messages")).document(message.getId());
        Map<String, Object> data = messageToMap(message);

        try {
            docRef.set(data, SetOptions.merge()).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("[Firebase] Failed to save message", e);
            throw new RuntimeException("Failed to save message to Firestore", e);
        }

        return message;
    }

    @Override
    public List<ChatMessageData> saveMessages(List<ChatMessageData> messages) {
        WriteBatch batch = firestore.batch();

        for (ChatMessageData m : messages) {
            if (m.getId() == null) m.setId(generateId());
            if (m.getCreatedAt() == null) m.setCreatedAt(Instant.now());
            m.setUpdatedAt(Instant.now());

            DocumentReference docRef = firestore.collection(prefixed("messages")).document(m.getId());
            batch.set(docRef, messageToMap(m), SetOptions.merge());
        }

        try {
            batch.commit().get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("[Firebase] Failed to batch save messages", e);
            throw new RuntimeException("Failed to batch save messages to Firestore", e);
        }

        return messages;
    }

    @Override
    public Optional<ChatMessageData> findMessageById(String messageId) {
        try {
            DocumentSnapshot doc = firestore.collection(prefixed("messages"))
                    .document(messageId).get().get();
            if (doc.exists()) {
                return Optional.of(mapToMessage(doc.getId(), doc.getData()));
            }
            return Optional.empty();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to find message in Firestore", e);
        }
    }

    @Override
    public List<ChatMessageData> findMessagesByConversation(String conversationId) {
        try {
            Query query = firestore.collection(prefixed("messages"))
                    .whereEqualTo("conversationId", conversationId)
                    .whereEqualTo("deleted", false)
                    .orderBy("createdAt", Query.Direction.ASCENDING);

            List<QueryDocumentSnapshot> docs = query.get().get().getDocuments();
            List<ChatMessageData> messages = new ArrayList<>();
            for (QueryDocumentSnapshot doc : docs) {
                messages.add(mapToMessage(doc.getId(), doc.getData()));
            }
            return messages;
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to find messages in Firestore", e);
        }
    }

    @Override
    public List<ChatMessageData> findMessagesByConversation(String conversationId, int offset, int limit) {
        try {
            // Firestore doesn't support native offset, so we use startAfter
            Query query = firestore.collection(prefixed("messages"))
                    .whereEqualTo("conversationId", conversationId)
                    .whereEqualTo("deleted", false)
                    .orderBy("createdAt", Query.Direction.ASCENDING)
                    .limit(offset + limit);

            List<QueryDocumentSnapshot> docs = query.get().get().getDocuments();
            List<ChatMessageData> messages = new ArrayList<>();
            
            int start = Math.min(offset, docs.size());
            int end = Math.min(start + limit, docs.size());
            for (int i = start; i < end; i++) {
                messages.add(mapToMessage(docs.get(i).getId(), docs.get(i).getData()));
            }
            return messages;
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to find messages in Firestore", e);
        }
    }

    @Override
    public List<ChatMessageData> findMessagesSince(String conversationId, Instant since) {
        try {
            Query query = firestore.collection(prefixed("messages"))
                    .whereEqualTo("conversationId", conversationId)
                    .whereEqualTo("deleted", false)
                    .whereGreaterThan("createdAt", since.toString())
                    .orderBy("createdAt", Query.Direction.ASCENDING);

            List<QueryDocumentSnapshot> docs = query.get().get().getDocuments();
            List<ChatMessageData> messages = new ArrayList<>();
            for (QueryDocumentSnapshot doc : docs) {
                messages.add(mapToMessage(doc.getId(), doc.getData()));
            }
            return messages;
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to find messages in Firestore", e);
        }
    }

    @Override
    public List<ChatMessageData> findUnreadMessages(String userId) {
        try {
            Query query = firestore.collection(prefixed("messages"))
                    .whereEqualTo("receiverId", userId)
                    .whereEqualTo("read", false)
                    .whereEqualTo("deleted", false)
                    .orderBy("createdAt", Query.Direction.ASCENDING);

            List<QueryDocumentSnapshot> docs = query.get().get().getDocuments();
            List<ChatMessageData> messages = new ArrayList<>();
            for (QueryDocumentSnapshot doc : docs) {
                messages.add(mapToMessage(doc.getId(), doc.getData()));
            }
            return messages;
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to find unread messages in Firestore", e);
        }
    }

    @Override
    public List<ChatMessageData> findUnreadMessages(String senderId, String receiverId) {
        try {
            Query query = firestore.collection(prefixed("messages"))
                    .whereEqualTo("senderId", senderId)
                    .whereEqualTo("receiverId", receiverId)
                    .whereEqualTo("read", false)
                    .whereEqualTo("deleted", false)
                    .orderBy("createdAt", Query.Direction.ASCENDING);

            List<QueryDocumentSnapshot> docs = query.get().get().getDocuments();
            List<ChatMessageData> messages = new ArrayList<>();
            for (QueryDocumentSnapshot doc : docs) {
                messages.add(mapToMessage(doc.getId(), doc.getData()));
            }
            return messages;
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to find unread messages in Firestore", e);
        }
    }

    @Override
    protected void doMarkMessagesAsRead(List<String> messageIds) {
        WriteBatch batch = firestore.batch();
        Map<String, Object> updates = new HashMap<>();
        updates.put("read", true);
        updates.put("readAt", Instant.now().toString());
        updates.put("updatedAt", Instant.now().toString());

        for (String id : messageIds) {
            DocumentReference docRef = firestore.collection(prefixed("messages")).document(id);
            batch.update(docRef, updates);
        }

        try {
            batch.commit().get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to mark messages as read in Firestore", e);
        }
    }

    @Override
    public void updateMessageStatus(String messageId, String status) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("status", status);
        updates.put("updatedAt", Instant.now().toString());

        try {
            firestore.collection(prefixed("messages")).document(messageId)
                    .update(updates).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to update message status in Firestore", e);
        }
    }

    @Override
    protected void doDeleteMessage(String messageId) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("deleted", true);
        updates.put("updatedAt", Instant.now().toString());

        try {
            firestore.collection(prefixed("messages")).document(messageId)
                    .update(updates).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to delete message in Firestore", e);
        }
    }

    // ========== Conversation Operations ==========

    @Override
    protected ConversationData doSaveConversation(ConversationData conv) {
        DocumentReference docRef = firestore.collection(prefixed("conversations")).document(conv.getId());
        Map<String, Object> data = conversationToMap(conv);

        try {
            docRef.set(data, SetOptions.merge()).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("[Firebase] Failed to save conversation", e);
            throw new RuntimeException("Failed to save conversation to Firestore", e);
        }

        return conv;
    }

    @Override
    public Optional<ConversationData> findConversationById(String conversationId) {
        try {
            DocumentSnapshot doc = firestore.collection(prefixed("conversations"))
                    .document(conversationId).get().get();
            if (doc.exists()) {
                return Optional.of(mapToConversation(doc.getId(), doc.getData()));
            }
            return Optional.empty();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to find conversation in Firestore", e);
        }
    }

    @Override
    public Optional<ConversationData> findConversationByUsers(String user1Id, String user2Id) {
        try {
            Query query = firestore.collection(prefixed("conversations"))
                    .whereEqualTo("user1Id", user1Id)
                    .whereEqualTo("user2Id", user2Id);

            List<QueryDocumentSnapshot> docs = query.get().get().getDocuments();
            if (!docs.isEmpty()) {
                return Optional.of(mapToConversation(docs.get(0).getId(), docs.get(0).getData()));
            }

            // Try reverse pair
            query = firestore.collection(prefixed("conversations"))
                    .whereEqualTo("user1Id", user2Id)
                    .whereEqualTo("user2Id", user1Id);
            docs = query.get().get().getDocuments();
            if (!docs.isEmpty()) {
                return Optional.of(mapToConversation(docs.get(0).getId(), docs.get(0).getData()));
            }

            return Optional.empty();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to find conversation in Firestore", e);
        }
    }

    @Override
    public List<ConversationData> findConversationsByUser(String userId) {
        try {
            List<ConversationData> conversations = new ArrayList<>();

            // Get conversations where user is user1
            Query q1 = firestore.collection(prefixed("conversations"))
                    .whereEqualTo("user1Id", userId);
            for (QueryDocumentSnapshot doc : q1.get().get().getDocuments()) {
                conversations.add(mapToConversation(doc.getId(), doc.getData()));
            }

            // Get conversations where user is user2
            Query q2 = firestore.collection(prefixed("conversations"))
                    .whereEqualTo("user2Id", userId);
            for (QueryDocumentSnapshot doc : q2.get().get().getDocuments()) {
                conversations.add(mapToConversation(doc.getId(), doc.getData()));
            }

            // Sort by lastMessageTime descending
            conversations.sort((a, b) -> {
                if (a.getLastMessageTime() == null) return 1;
                if (b.getLastMessageTime() == null) return -1;
                return b.getLastMessageTime().compareTo(a.getLastMessageTime());
            });

            return conversations;
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to find conversations in Firestore", e);
        }
    }

    @Override
    public void updateConversationLastMessage(String conversationId, String lastMessage, Instant lastMessageTime) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("lastMessage", lastMessage);
        updates.put("lastMessageTime", lastMessageTime.toString());
        updates.put("updatedAt", Instant.now().toString());

        // Increment messageCount atomically
        try {
            firestore.collection(prefixed("conversations")).document(conversationId)
                    .update(updates).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to update conversation in Firestore", e);
        }
    }

    @Override
    public void updateConversationStatus(String conversationId, String status) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("status", status);
        updates.put("updatedAt", Instant.now().toString());

        try {
            firestore.collection(prefixed("conversations")).document(conversationId)
                    .update(updates).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to update conversation status in Firestore", e);
        }
    }

    @Override
    public void setConversationBlock(String conversationId, String userId, boolean blocked) {
        try {
            Optional<ConversationData> conv = findConversationById(conversationId);
            if (conv.isEmpty()) return;

            String blockField = userId.equals(conv.get().getUser1Id()) ? "blockedByUser1" : "blockedByUser2";

            Map<String, Object> updates = new HashMap<>();
            updates.put(blockField, blocked);
            updates.put("status", blocked ? "INACTIVE" : "ACTIVE");
            updates.put("updatedAt", Instant.now().toString());

            firestore.collection(prefixed("conversations")).document(conversationId)
                    .update(updates).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to update conversation block in Firestore", e);
        }
    }

    @Override
    protected void doDeleteConversation(String conversationId) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("status", "INACTIVE");
        updates.put("updatedAt", Instant.now().toString());

        try {
            firestore.collection(prefixed("conversations")).document(conversationId)
                    .update(updates).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to delete conversation in Firestore", e);
        }
    }

    // ========== Private Helpers ==========

    private void initializeFirebase() {
        ChatStorageProperties.FirebaseConfig config = properties.getFirebase();

        try {
            FileInputStream serviceAccount = new FileInputStream(config.getCredentialPath());

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(com.google.auth.oauth2.GoogleCredentials.fromStream(serviceAccount))
                    .setProjectId(config.getProjectId())
                    .build();

            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(options);
                log.info("[Firebase] Firebase Admin SDK initialized for project: {}", config.getProjectId());
            }

            this.firestore = FirestoreClient.getFirestore();
            log.info("[Firebase] Firestore connected successfully");

        } catch (IOException e) {
            log.error("[Firebase] Failed to initialize Firebase: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize Firebase", e);
        }
    }

    // ========== Map Mapping ==========

    private Map<String, Object> messageToMap(ChatMessageData msg) {
        Map<String, Object> map = new HashMap<>();
        map.put("conversationId", msg.getConversationId());
        map.put("senderId", msg.getSenderId());
        map.put("senderEmail", msg.getSenderEmail());
        map.put("senderName", msg.getSenderName());
        map.put("receiverId", msg.getReceiverId());
        map.put("receiverEmail", msg.getReceiverEmail());
        map.put("content", msg.getContent());
        map.put("messageType", msg.getMessageType());
        map.put("mediaUrl", msg.getMediaUrl());
        map.put("mediaMimeType", msg.getMediaMimeType());
        map.put("mediaSize", msg.getMediaSize());
        map.put("read", msg.isRead());
        map.put("readAt", msg.getReadAt() != null ? msg.getReadAt().toString() : null);
        map.put("status", msg.getStatus());
        map.put("deleted", msg.isDeleted());
        map.put("createdAt", msg.getCreatedAt() != null ? msg.getCreatedAt().toString() : null);
        map.put("updatedAt", msg.getUpdatedAt() != null ? msg.getUpdatedAt().toString() : null);
        map.put("tenantId", msg.getTenantId());
        if (msg.getMetadata() != null) {
            map.put("metadata", msg.getMetadata());
        }
        return map;
    }

    private ChatMessageData mapToMessage(String id, Map<String, Object> data) {
        if (data == null) return null;
        return ChatMessageData.builder()
                .id(id)
                .conversationId((String) data.get("conversationId"))
                .senderId((String) data.get("senderId"))
                .senderEmail((String) data.get("senderEmail"))
                .senderName((String) data.get("senderName"))
                .receiverId((String) data.get("receiverId"))
                .receiverEmail((String) data.get("receiverEmail"))
                .content((String) data.get("content"))
                .messageType((String) data.get("messageType"))
                .mediaUrl((String) data.get("mediaUrl"))
                .mediaMimeType((String) data.get("mediaMimeType"))
                .mediaSize(data.get("mediaSize") != null ? ((Number) data.get("mediaSize")).longValue() : null)
                .read(data.get("read") != null ? (Boolean) data.get("read") : false)
                .readAt(data.get("readAt") != null ? Instant.parse((String) data.get("readAt")) : null)
                .status((String) data.get("status"))
                .deleted(data.get("deleted") != null ? (Boolean) data.get("deleted") : false)
                .createdAt(data.get("createdAt") != null ? Instant.parse((String) data.get("createdAt")) : null)
                .updatedAt(data.get("updatedAt") != null ? Instant.parse((String) data.get("updatedAt")) : null)
                .tenantId((String) data.get("tenantId"))
                .metadata(data.get("metadata") instanceof Map ? (Map<String, Object>) data.get("metadata") : null)
                .build();
    }

    private Map<String, Object> conversationToMap(ConversationData conv) {
        Map<String, Object> map = new HashMap<>();
        map.put("user1Id", conv.getUser1Id());
        map.put("user1Email", conv.getUser1Email());
        map.put("user2Id", conv.getUser2Id());
        map.put("user2Email", conv.getUser2Email());
        map.put("lastMessage", conv.getLastMessage());
        map.put("lastMessageTime", conv.getLastMessageTime() != null ? conv.getLastMessageTime().toString() : null);
        map.put("status", conv.getStatus());
        map.put("blockedByUser1", conv.isBlockedByUser1());
        map.put("blockedByUser2", conv.isBlockedByUser2());
        map.put("messageCount", conv.getMessageCount());
        map.put("unreadCountUser1", conv.getUnreadCountUser1());
        map.put("unreadCountUser2", conv.getUnreadCountUser2());
        map.put("createdAt", conv.getCreatedAt() != null ? conv.getCreatedAt().toString() : null);
        map.put("updatedAt", conv.getUpdatedAt() != null ? conv.getUpdatedAt().toString() : null);
        map.put("tenantId", conv.getTenantId());
        if (conv.getMetadata() != null) {
            map.put("metadata", conv.getMetadata());
        }
        return map;
    }

    private ConversationData mapToConversation(String id, Map<String, Object> data) {
        if (data == null) return null;
        return ConversationData.builder()
                .id(id)
                .user1Id((String) data.get("user1Id"))
                .user1Email((String) data.get("user1Email"))
                .user2Id((String) data.get("user2Id"))
                .user2Email((String) data.get("user2Email"))
                .lastMessage((String) data.get("lastMessage"))
                .lastMessageTime(data.get("lastMessageTime") != null ?
                        Instant.parse((String) data.get("lastMessageTime")) : null)
                .status((String) data.get("status"))
                .blockedByUser1(data.get("blockedByUser1") != null ? (Boolean) data.get("blockedByUser1") : false)
                .blockedByUser2(data.get("blockedByUser2") != null ? (Boolean) data.get("blockedByUser2") : false)
                .messageCount(data.get("messageCount") != null ? ((Number) data.get("messageCount")).longValue() : 0L)
                .unreadCountUser1(data.get("unreadCountUser1") != null ? ((Number) data.get("unreadCountUser1")).intValue() : 0)
                .unreadCountUser2(data.get("unreadCountUser2") != null ? ((Number) data.get("unreadCountUser2")).intValue() : 0)
                .createdAt(data.get("createdAt") != null ? Instant.parse((String) data.get("createdAt")) : null)
                .updatedAt(data.get("updatedAt") != null ? Instant.parse((String) data.get("updatedAt")) : null)
                .tenantId((String) data.get("tenantId"))
                .metadata(data.get("metadata") instanceof Map ? (Map<String, Object>) data.get("metadata") : null)
                .build();
    }
}
