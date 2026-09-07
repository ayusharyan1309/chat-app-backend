package com.ayush.chat.storage.provider;

import com.ayush.chat.storage.StorageType;
import com.ayush.chat.storage.config.ChatStorageProperties;
import com.ayush.chat.storage.dto.ChatMessageData;
import com.ayush.chat.storage.dto.ConversationData;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB-based chat storage provider.
 * 
 * Uses the official MongoDB Java Driver directly (no Spring Data MongoDB needed).
 * Collections are created automatically on initialization.
 * 
 * Storage model:
 * - chat_messages: Document collection for messages
 * - chat_conversations: Document collection for conversations
 */
@Slf4j
public class MongoChatStorageProvider extends AbstractChatStorageProvider {

    private final MongoClient mongoClient;
    private final MongoDatabase database;

    public MongoChatStorageProvider(ChatStorageProperties properties) {
        super(properties);
        this.mongoClient = createMongoClient(properties);
        this.database = mongoClient.getDatabase(properties.getMongodb().getDatabase());
        initializeCollections();
    }

    @Override
    public StorageType getStorageType() {
        return StorageType.MONGODB;
    }

    @Override
    public boolean isHealthy() {
        try {
            database.runCommand(new Document("ping", 1));
            return true;
        } catch (Exception e) {
            log.error("[MongoDB] Health check failed", e);
            return false;
        }
    }

    // ========== Message Operations ==========

    @Override
    protected ChatMessageData doSaveMessage(ChatMessageData message) {
        MongoCollection<Document> collection = database.getCollection(prefixed("messages"));
        Document doc = messageToDocument(message);
        
        // Upsert: if ID exists, update; otherwise insert
        collection.replaceOne(
                Filters.eq("_id", message.getId()),
                doc,
                new com.mongodb.client.model.ReplaceOptions().upsert(true)
        );

        return message;
    }

    @Override
    public List<ChatMessageData> saveMessages(List<ChatMessageData> messages) {
        MongoCollection<Document> collection = database.getCollection(prefixed("messages"));
        
        List<Document> docs = new ArrayList<>();
        for (ChatMessageData m : messages) {
            if (m.getId() == null) m.setId(generateId());
            if (m.getCreatedAt() == null) m.setCreatedAt(Instant.now());
            m.setUpdatedAt(Instant.now());
            docs.add(messageToDocument(m));
        }
        
        collection.insertMany(docs);
        return messages;
    }

    @Override
    public Optional<ChatMessageData> findMessageById(String messageId) {
        MongoCollection<Document> collection = database.getCollection(prefixed("messages"));
        Document doc = collection.find(Filters.eq("_id", messageId)).first();
        if (doc != null) {
            return Optional.of(documentToMessage(doc));
        }
        return Optional.empty();
    }

    @Override
    public List<ChatMessageData> findMessagesByConversation(String conversationId) {
        MongoCollection<Document> collection = database.getCollection(prefixed("messages"));
        List<ChatMessageData> messages = new ArrayList<>();
        
        collection.find(Filters.eq("conversationId", conversationId))
                .sort(new Document("createdAt", 1))
                .filter(Filters.eq("deleted", false))
                .forEach(doc -> messages.add(documentToMessage(doc)));
        
        return messages;
    }

    @Override
    public List<ChatMessageData> findMessagesByConversation(String conversationId, int offset, int limit) {
        MongoCollection<Document> collection = database.getCollection(prefixed("messages"));
        List<ChatMessageData> messages = new ArrayList<>();
        
        collection.find(Filters.eq("conversationId", conversationId))
                .sort(new Document("createdAt", 1))
                .skip(offset)
                .limit(limit)
                .filter(Filters.eq("deleted", false))
                .forEach(doc -> messages.add(documentToMessage(doc)));
        
        return messages;
    }

    @Override
    public List<ChatMessageData> findMessagesSince(String conversationId, Instant since) {
        MongoCollection<Document> collection = database.getCollection(prefixed("messages"));
        List<ChatMessageData> messages = new ArrayList<>();
        
        collection.find(Filters.and(
                        Filters.eq("conversationId", conversationId),
                        Filters.gt("createdAt", since.toString()),
                        Filters.eq("deleted", false)))
                .sort(new Document("createdAt", 1))
                .forEach(doc -> messages.add(documentToMessage(doc)));
        
        return messages;
    }

    @Override
    public List<ChatMessageData> findUnreadMessages(String userId) {
        MongoCollection<Document> collection = database.getCollection(prefixed("messages"));
        List<ChatMessageData> messages = new ArrayList<>();
        
        collection.find(Filters.and(
                        Filters.eq("receiverId", userId),
                        Filters.eq("read", false),
                        Filters.eq("deleted", false)))
                .sort(new Document("createdAt", 1))
                .forEach(doc -> messages.add(documentToMessage(doc)));
        
        return messages;
    }

    @Override
    public List<ChatMessageData> findUnreadMessages(String senderId, String receiverId) {
        MongoCollection<Document> collection = database.getCollection(prefixed("messages"));
        List<ChatMessageData> messages = new ArrayList<>();
        
        collection.find(Filters.and(
                        Filters.eq("senderId", senderId),
                        Filters.eq("receiverId", receiverId),
                        Filters.eq("read", false),
                        Filters.eq("deleted", false)))
                .sort(new Document("createdAt", 1))
                .forEach(doc -> messages.add(documentToMessage(doc)));
        
        return messages;
    }

    @Override
    protected void doMarkMessagesAsRead(List<String> messageIds) {
        MongoCollection<Document> collection = database.getCollection(prefixed("messages"));
        collection.updateMany(
                Filters.in("_id", messageIds),
                Updates.combine(
                        Updates.set("read", true),
                        Updates.set("readAt", Instant.now().toString()),
                        Updates.set("updatedAt", Instant.now().toString())
                )
        );
    }

    @Override
    public void updateMessageStatus(String messageId, String status) {
        MongoCollection<Document> collection = database.getCollection(prefixed("messages"));
        collection.updateOne(
                Filters.eq("_id", messageId),
                Updates.combine(
                        Updates.set("status", status),
                        Updates.set("updatedAt", Instant.now().toString())
                )
        );
    }

    @Override
    protected void doDeleteMessage(String messageId) {
        MongoCollection<Document> collection = database.getCollection(prefixed("messages"));
        collection.updateOne(
                Filters.eq("_id", messageId),
                Updates.combine(
                        Updates.set("deleted", true),
                        Updates.set("updatedAt", Instant.now().toString())
                )
        );
    }

    // ========== Conversation Operations ==========

    @Override
    protected ConversationData doSaveConversation(ConversationData conv) {
        MongoCollection<Document> collection = database.getCollection(prefixed("conversations"));
        Document doc = conversationToDocument(conv);
        
        collection.replaceOne(
                Filters.eq("_id", conv.getId()),
                doc,
                new com.mongodb.client.model.ReplaceOptions().upsert(true)
        );
        
        return conv;
    }

    @Override
    public Optional<ConversationData> findConversationById(String conversationId) {
        MongoCollection<Document> collection = database.getCollection(prefixed("conversations"));
        Document doc = collection.find(Filters.eq("_id", conversationId)).first();
        if (doc != null) {
            return Optional.of(documentToConversation(doc));
        }
        return Optional.empty();
    }

    @Override
    public Optional<ConversationData> findConversationByUsers(String user1Id, String user2Id) {
        MongoCollection<Document> collection = database.getCollection(prefixed("conversations"));
        Document doc = collection.find(Filters.or(
                Filters.and(Filters.eq("user1Id", user1Id), Filters.eq("user2Id", user2Id)),
                Filters.and(Filters.eq("user1Id", user2Id), Filters.eq("user2Id", user1Id))
        )).first();
        
        if (doc != null) {
            return Optional.of(documentToConversation(doc));
        }
        return Optional.empty();
    }

    @Override
    public List<ConversationData> findConversationsByUser(String userId) {
        MongoCollection<Document> collection = database.getCollection(prefixed("conversations"));
        List<ConversationData> conversations = new ArrayList<>();
        
        collection.find(Filters.or(
                        Filters.eq("user1Id", userId),
                        Filters.eq("user2Id", userId)))
                .sort(new Document("lastMessageTime", -1))
                .forEach(doc -> conversations.add(documentToConversation(doc)));
        
        return conversations;
    }

    @Override
    public void updateConversationLastMessage(String conversationId, String lastMessage, Instant lastMessageTime) {
        MongoCollection<Document> collection = database.getCollection(prefixed("conversations"));
        collection.updateOne(
                Filters.eq("_id", conversationId),
                Updates.combine(
                        Updates.set("lastMessage", lastMessage),
                        Updates.set("lastMessageTime", lastMessageTime.toString()),
                        Updates.inc("messageCount", 1),
                        Updates.set("updatedAt", Instant.now().toString())
                )
        );
    }

    @Override
    public void updateConversationStatus(String conversationId, String status) {
        MongoCollection<Document> collection = database.getCollection(prefixed("conversations"));
        collection.updateOne(
                Filters.eq("_id", conversationId),
                Updates.combine(
                        Updates.set("status", status),
                        Updates.set("updatedAt", Instant.now().toString())
                )
        );
    }

    @Override
    public void setConversationBlock(String conversationId, String userId, boolean blocked) {
        Optional<ConversationData> conv = findConversationById(conversationId);
        if (conv.isEmpty()) return;

        MongoCollection<Document> collection = database.getCollection(prefixed("conversations"));
        String blockField = userId.equals(conv.get().getUser1Id()) ? "blockedByUser1" : "blockedByUser2";

        UpdateResult result = collection.updateOne(
                Filters.eq("_id", conversationId),
                Updates.combine(
                        Updates.set(blockField, blocked),
                        Updates.set("status", blocked ? "INACTIVE" : "ACTIVE"),
                        Updates.set("updatedAt", Instant.now().toString())
                )
        );
    }

    @Override
    protected void doDeleteConversation(String conversationId) {
        MongoCollection<Document> collection = database.getCollection(prefixed("conversations"));
        collection.updateOne(
                Filters.eq("_id", conversationId),
                Updates.combine(
                        Updates.set("status", "INACTIVE"),
                        Updates.set("updatedAt", Instant.now().toString())
                )
        );
    }

    // ========== Private Helpers ==========

    private MongoClient createMongoClient(ChatStorageProperties props) {
        String uri = props.getEffectiveMongoUri();
        log.info("[MongoDB] Connecting to: {}", uri);

        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .applyToSocketSettings(builder -> 
                        builder.connectTimeout((int) props.getConnectionTimeout(), java.util.concurrent.TimeUnit.MILLISECONDS))
                .applyToConnectionPoolSettings(builder -> 
                        builder.maxConnectionIdleTime(props.getConnectionTimeout(), java.util.concurrent.TimeUnit.MILLISECONDS))
                .build();

        return MongoClients.create(settings);
    }

    private void initializeCollections() {
        log.info("[MongoDB] Initializing collections");

        // Create indexes for messages collection
        MongoCollection<Document> messages = database.getCollection(prefixed("messages"));
        messages.createIndex(new Document("conversationId", 1).append("createdAt", 1));
        messages.createIndex(new Document("senderId", 1));
        messages.createIndex(new Document("receiverId", 1));
        messages.createIndex(new Document("receiverId", 1).append("read", 1));
        messages.createIndex(new Document("tenantId", 1));

        // Create indexes for conversations collection
        MongoCollection<Document> conversations = database.getCollection(prefixed("conversations"));
        conversations.createIndex(
                new Document("user1Id", 1).append("user2Id", 1),
                new IndexOptions().unique(true));
        conversations.createIndex(new Document("user1Id", 1));
        conversations.createIndex(new Document("user2Id", 1));
        conversations.createIndex(new Document("lastMessageTime", -1));
        conversations.createIndex(new Document("tenantId", 1));

        log.info("[MongoDB] Collections initialized successfully");
    }

    // ========== Document Mapping ==========

    private Document messageToDocument(ChatMessageData msg) {
        Document doc = new Document();
        doc.put("_id", msg.getId());
        doc.put("conversationId", msg.getConversationId());
        doc.put("senderId", msg.getSenderId());
        doc.put("senderEmail", msg.getSenderEmail());
        doc.put("senderName", msg.getSenderName());
        doc.put("receiverId", msg.getReceiverId());
        doc.put("receiverEmail", msg.getReceiverEmail());
        doc.put("content", msg.getContent());
        doc.put("messageType", msg.getMessageType());
        doc.put("mediaUrl", msg.getMediaUrl());
        doc.put("mediaMimeType", msg.getMediaMimeType());
        doc.put("mediaSize", msg.getMediaSize());
        doc.put("read", msg.isRead());
        doc.put("readAt", msg.getReadAt() != null ? msg.getReadAt().toString() : null);
        doc.put("status", msg.getStatus());
        doc.put("deleted", msg.isDeleted());
        doc.put("createdAt", msg.getCreatedAt() != null ? msg.getCreatedAt().toString() : null);
        doc.put("updatedAt", msg.getUpdatedAt() != null ? msg.getUpdatedAt().toString() : null);
        doc.put("metadata", msg.getMetadata());
        doc.put("tenantId", msg.getTenantId());
        return doc;
    }

    private ChatMessageData documentToMessage(Document doc) {
        return ChatMessageData.builder()
                .id(doc.getString("_id"))
                .conversationId(doc.getString("conversationId"))
                .senderId(doc.getString("senderId"))
                .senderEmail(doc.getString("senderEmail"))
                .senderName(doc.getString("senderName"))
                .receiverId(doc.getString("receiverId"))
                .receiverEmail(doc.getString("receiverEmail"))
                .content(doc.getString("content"))
                .messageType(doc.getString("messageType"))
                .mediaUrl(doc.getString("mediaUrl"))
                .mediaMimeType(doc.getString("mediaMimeType"))
                .mediaSize(doc.getLong("mediaSize"))
                .read(doc.getBoolean("read", false))
                .readAt(doc.getString("readAt") != null ? Instant.parse(doc.getString("readAt")) : null)
                .status(doc.getString("status"))
                .deleted(doc.getBoolean("deleted", false))
                .createdAt(doc.getString("createdAt") != null ? Instant.parse(doc.getString("createdAt")) : null)
                .updatedAt(doc.getString("updatedAt") != null ? Instant.parse(doc.getString("updatedAt")) : null)
                .tenantId(doc.getString("tenantId"))
                .build();
    }

    private Document conversationToDocument(ConversationData conv) {
        Document doc = new Document();
        doc.put("_id", conv.getId());
        doc.put("user1Id", conv.getUser1Id());
        doc.put("user1Email", conv.getUser1Email());
        doc.put("user2Id", conv.getUser2Id());
        doc.put("user2Email", conv.getUser2Email());
        doc.put("lastMessage", conv.getLastMessage());
        doc.put("lastMessageTime", conv.getLastMessageTime() != null ? conv.getLastMessageTime().toString() : null);
        doc.put("status", conv.getStatus());
        doc.put("blockedByUser1", conv.isBlockedByUser1());
        doc.put("blockedByUser2", conv.isBlockedByUser2());
        doc.put("messageCount", conv.getMessageCount());
        doc.put("unreadCountUser1", conv.getUnreadCountUser1());
        doc.put("unreadCountUser2", conv.getUnreadCountUser2());
        doc.put("createdAt", conv.getCreatedAt() != null ? conv.getCreatedAt().toString() : null);
        doc.put("updatedAt", conv.getUpdatedAt() != null ? conv.getUpdatedAt().toString() : null);
        doc.put("metadata", conv.getMetadata());
        doc.put("tenantId", conv.getTenantId());
        return doc;
    }

    private ConversationData documentToConversation(Document doc) {
        return ConversationData.builder()
                .id(doc.getString("_id"))
                .user1Id(doc.getString("user1Id"))
                .user1Email(doc.getString("user1Email"))
                .user2Id(doc.getString("user2Id"))
                .user2Email(doc.getString("user2Email"))
                .lastMessage(doc.getString("lastMessage"))
                .lastMessageTime(doc.getString("lastMessageTime") != null ?
                        Instant.parse(doc.getString("lastMessageTime")) : null)
                .status(doc.getString("status"))
                .blockedByUser1(doc.getBoolean("blockedByUser1", false))
                .blockedByUser2(doc.getBoolean("blockedByUser2", false))
                .messageCount(doc.getLong("messageCount") != null ? doc.getLong("messageCount") : 0L)
                .unreadCountUser1(doc.getInteger("unreadCountUser1", 0))
                .unreadCountUser2(doc.getInteger("unreadCountUser2", 0))
                .createdAt(doc.getString("createdAt") != null ? Instant.parse(doc.getString("createdAt")) : null)
                .updatedAt(doc.getString("updatedAt") != null ? Instant.parse(doc.getString("updatedAt")) : null)
                .tenantId(doc.getString("tenantId"))
                .build();
    }
}
