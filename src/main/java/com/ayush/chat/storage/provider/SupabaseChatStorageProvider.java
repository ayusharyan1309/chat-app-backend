package com.ayush.chat.storage.provider;

import com.ayush.chat.storage.StorageType;
import com.ayush.chat.storage.config.ChatStorageProperties;
import com.ayush.chat.storage.dto.ChatMessageData;
import com.ayush.chat.storage.dto.ConversationData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Supabase chat storage provider using Supabase REST API (PostgREST).
 * 
 * Supabase provides a full Postgres database with RESTful API access.
 * This provider uses the PostgREST API to interact with the database.
 * 
 * Tables are auto-created via SQL RPC on initialization.
 * 
 * <p>Setup requirements:</p>
 * <ul>
 *   <li>Supabase project URL</li>
 *   <li>Supabase service_role API key (for server-side access)</li>
 *   <li>HTTP Client (JDK 11+ built-in HttpClient)</li>
 * </ul>
 */
@Slf4j
public class SupabaseChatStorageProvider extends AbstractChatStorageProvider {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String schema;

    public SupabaseChatStorageProvider(ChatStorageProperties properties) {
        super(properties);
        this.objectMapper = new ObjectMapper();
        this.baseUrl = properties.getSupabase().getUrl();
        this.apiKey = properties.getSupabase().getApiKey();
        this.schema = properties.getSupabase().getSchema();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectionTimeout()))
                .build();

        initializeTables();
    }

    @Override
    public StorageType getStorageType() {
        return StorageType.SUPABASE;
    }

    @Override
    public boolean isHealthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/rest/v1/"))
                    .header("apikey", apiKey)
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.error("[Supabase] Health check failed", e);
            return false;
        }
    }

    // ========== Message Operations ==========

    @Override
    protected ChatMessageData doSaveMessage(ChatMessageData message) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("id", message.getId());
        body.put("conversation_id", message.getConversationId());
        body.put("sender_id", message.getSenderId());
        body.put("sender_email", message.getSenderEmail());
        body.put("sender_name", message.getSenderName());
        body.put("receiver_id", message.getReceiverId());
        body.put("receiver_email", message.getReceiverEmail());
        body.put("content", message.getContent());
        body.put("message_type", message.getMessageType());
        body.put("media_url", message.getMediaUrl());
        body.put("media_mime_type", message.getMediaMimeType());
        body.put("media_size", message.getMediaSize());
        body.put("is_read", message.isRead());
        body.put("status", message.getStatus());
        body.put("is_deleted", message.isDeleted());
        body.put("created_at", message.getCreatedAt() != null ? message.getCreatedAt().toString() : null);
        body.put("updated_at", message.getUpdatedAt() != null ? message.getUpdatedAt().toString() : null);
        body.put("tenant_id", message.getTenantId());

        // Upsert: ON CONFLICT (id) DO UPDATE
        postRequest("/rest/v1/" + prefixed("messages"), body.toString(), 
                Map.of("Prefer", "resolution=merge-duplicates"));

        return message;
    }

    @Override
    public List<ChatMessageData> saveMessages(List<ChatMessageData> messages) {
        ArrayNode arrayNode = objectMapper.createArrayNode();
        for (ChatMessageData m : messages) {
            if (m.getId() == null) m.setId(generateId());
            if (m.getCreatedAt() == null) m.setCreatedAt(Instant.now());
            m.setUpdatedAt(Instant.now());

            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", m.getId());
            node.put("conversation_id", m.getConversationId());
            node.put("sender_id", m.getSenderId());
            node.put("sender_email", m.getSenderEmail());
            node.put("sender_name", m.getSenderName());
            node.put("receiver_id", m.getReceiverId());
            node.put("receiver_email", m.getReceiverEmail());
            node.put("content", m.getContent());
            node.put("message_type", m.getMessageType());
            node.put("status", m.getStatus());
            node.put("is_deleted", m.isDeleted());
            node.put("created_at", m.getCreatedAt() != null ? m.getCreatedAt().toString() : null);
            node.put("updated_at", m.getUpdatedAt() != null ? m.getUpdatedAt().toString() : null);
            node.put("tenant_id", m.getTenantId());
            arrayNode.add(node);
        }

        postRequest("/rest/v1/" + prefixed("messages"), arrayNode.toString(),
                Map.of("Prefer", "resolution=merge-duplicates"));

        return messages;
    }

    @Override
    public Optional<ChatMessageData> findMessageById(String messageId) {
        String response = getRequest("/rest/v1/" + prefixed("messages") + 
                "?id=eq." + messageId + "&is_deleted=eq.false");
        
        JsonNode nodes = parseResponse(response);
        if (nodes.isArray() && nodes.size() > 0) {
            return Optional.of(jsonToMessage(nodes.get(0)));
        }
        return Optional.empty();
    }

    @Override
    public List<ChatMessageData> findMessagesByConversation(String conversationId) {
        String response = getRequest("/rest/v1/" + prefixed("messages") + 
                "?conversation_id=eq." + conversationId + 
                "&is_deleted=eq.false&order=created_at.asc");

        return parseMessageList(response);
    }

    @Override
    public List<ChatMessageData> findMessagesByConversation(String conversationId, int offset, int limit) {
        String response = getRequest("/rest/v1/" + prefixed("messages") + 
                "?conversation_id=eq." + conversationId + 
                "&is_deleted=eq.false&order=created_at.asc" +
                "&limit=" + limit + "&offset=" + offset);

        return parseMessageList(response);
    }

    @Override
    public List<ChatMessageData> findMessagesSince(String conversationId, Instant since) {
        String response = getRequest("/rest/v1/" + prefixed("messages") + 
                "?conversation_id=eq." + conversationId + 
                "&is_deleted=eq.false" +
                "&created_at=gt." + since.toString() +
                "&order=created_at.asc");

        return parseMessageList(response);
    }

    @Override
    public List<ChatMessageData> findUnreadMessages(String userId) {
        String response = getRequest("/rest/v1/" + prefixed("messages") + 
                "?receiver_id=eq." + userId + 
                "&is_read=eq.false&is_deleted=eq.false" +
                "&order=created_at.asc");

        return parseMessageList(response);
    }

    @Override
    public List<ChatMessageData> findUnreadMessages(String senderId, String receiverId) {
        String response = getRequest("/rest/v1/" + prefixed("messages") + 
                "?sender_id=eq." + senderId + 
                "&receiver_id=eq." + receiverId +
                "&is_read=eq.false&is_deleted=eq.false" +
                "&order=created_at.asc");

        return parseMessageList(response);
    }

    @Override
    protected void doMarkMessagesAsRead(List<String> messageIds) {
        for (String id : messageIds) {
            ObjectNode update = objectMapper.createObjectNode();
            update.put("is_read", true);
            update.put("read_at", Instant.now().toString());
            update.put("updated_at", Instant.now().toString());

            patchRequest("/rest/v1/" + prefixed("messages") + "?id=eq." + id, update.toString());
        }
    }

    @Override
    public void updateMessageStatus(String messageId, String status) {
        ObjectNode update = objectMapper.createObjectNode();
        update.put("status", status);
        update.put("updated_at", Instant.now().toString());

        patchRequest("/rest/v1/" + prefixed("messages") + "?id=eq." + messageId, update.toString());
    }

    @Override
    protected void doDeleteMessage(String messageId) {
        ObjectNode update = objectMapper.createObjectNode();
        update.put("is_deleted", true);
        update.put("updated_at", Instant.now().toString());

        patchRequest("/rest/v1/" + prefixed("messages") + "?id=eq." + messageId, update.toString());
    }

    // ========== Conversation Operations ==========

    @Override
    protected ConversationData doSaveConversation(ConversationData conv) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("id", conv.getId());
        body.put("user1_id", conv.getUser1Id());
        body.put("user1_email", conv.getUser1Email());
        body.put("user2_id", conv.getUser2Id());
        body.put("user2_email", conv.getUser2Email());
        body.put("last_message", conv.getLastMessage());
        body.put("last_message_time", conv.getLastMessageTime() != null ? conv.getLastMessageTime().toString() : null);
        body.put("status", conv.getStatus());
        body.put("blocked_by_user1", conv.isBlockedByUser1());
        body.put("blocked_by_user2", conv.isBlockedByUser2());
        body.put("message_count", conv.getMessageCount());
        body.put("created_at", conv.getCreatedAt() != null ? conv.getCreatedAt().toString() : null);
        body.put("updated_at", conv.getUpdatedAt() != null ? conv.getUpdatedAt().toString() : null);
        body.put("tenant_id", conv.getTenantId());

        postRequest("/rest/v1/" + prefixed("conversations"), body.toString(),
                Map.of("Prefer", "resolution=merge-duplicates"));

        return conv;
    }

    @Override
    public Optional<ConversationData> findConversationById(String conversationId) {
        String response = getRequest("/rest/v1/" + prefixed("conversations") + 
                "?id=eq." + conversationId);

        JsonNode nodes = parseResponse(response);
        if (nodes.isArray() && nodes.size() > 0) {
            return Optional.of(jsonToConversation(nodes.get(0)));
        }
        return Optional.empty();
    }

    @Override
    public Optional<ConversationData> findConversationByUsers(String user1Id, String user2Id) {
        String response = getRequest("/rest/v1/" + prefixed("conversations") + 
                "?or=(and(user1_id.eq." + user1Id + ",user2_id.eq." + user2Id + 
                "),and(user1_id.eq." + user2Id + ",user2_id.eq." + user1Id + "))");

        JsonNode nodes = parseResponse(response);
        if (nodes.isArray() && nodes.size() > 0) {
            return Optional.of(jsonToConversation(nodes.get(0)));
        }
        return Optional.empty();
    }

    @Override
    public List<ConversationData> findConversationsByUser(String userId) {
        String response = getRequest("/rest/v1/" + prefixed("conversations") + 
                "?or=(user1_id.eq." + userId + ",user2_id.eq." + userId + 
                ")&order=last_message_time.desc.nullsfirst");

        JsonNode nodes = parseResponse(response);
        List<ConversationData> conversations = new ArrayList<>();
        if (nodes.isArray()) {
            for (JsonNode node : nodes) {
                conversations.add(jsonToConversation(node));
            }
        }
        return conversations;
    }

    @Override
    public void updateConversationLastMessage(String conversationId, String lastMessage, Instant lastMessageTime) {
        ObjectNode update = objectMapper.createObjectNode();
        update.put("last_message", lastMessage);
        update.put("last_message_time", lastMessageTime.toString());
        update.put("updated_at", Instant.now().toString());

        patchRequest("/rest/v1/" + prefixed("conversations") + "?id=eq." + conversationId, update.toString());
    }

    @Override
    public void updateConversationStatus(String conversationId, String status) {
        ObjectNode update = objectMapper.createObjectNode();
        update.put("status", status);
        update.put("updated_at", Instant.now().toString());

        patchRequest("/rest/v1/" + prefixed("conversations") + "?id=eq." + conversationId, update.toString());
    }

    @Override
    public void setConversationBlock(String conversationId, String userId, boolean blocked) {
        try {
            Optional<ConversationData> conv = findConversationById(conversationId);
            if (conv.isEmpty()) return;

            String blockField = userId.equals(conv.get().getUser1Id()) ? "blocked_by_user1" : "blocked_by_user2";

            ObjectNode update = objectMapper.createObjectNode();
            update.put(blockField, blocked);
            update.put("status", blocked ? "INACTIVE" : "ACTIVE");
            update.put("updated_at", Instant.now().toString());

            patchRequest("/rest/v1/" + prefixed("conversations") + "?id=eq." + conversationId, update.toString());
        } catch (Exception e) {
            throw new RuntimeException("Failed to update conversation block in Supabase", e);
        }
    }

    @Override
    protected void doDeleteConversation(String conversationId) {
        ObjectNode update = objectMapper.createObjectNode();
        update.put("status", "INACTIVE");
        update.put("updated_at", Instant.now().toString());

        patchRequest("/rest/v1/" + prefixed("conversations") + "?id=eq." + conversationId, update.toString());
    }

    // ========== HTTP Helpers ==========

    private String getRequest(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("apikey", apiKey)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.error("[Supabase] GET {} failed: {} - {}", path, response.statusCode(), response.body());
                throw new RuntimeException("Supabase GET failed: " + response.statusCode());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Supabase GET request failed", e);
        }
    }

    private String postRequest(String path, String body) {
        return postRequest(path, body, Map.of());
    }

    private String postRequest(String path, String body, Map<String, String> extraHeaders) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("apikey", apiKey)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30));

            extraHeaders.forEach(builder::header);

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.error("[Supabase] POST {} failed: {} - {}", path, response.statusCode(), response.body());
                throw new RuntimeException("Supabase POST failed: " + response.statusCode());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Supabase POST request failed", e);
        }
    }

    private void patchRequest(String path, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("apikey", apiKey)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Prefer", "return=minimal")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.error("[Supabase] PATCH {} failed: {} - {}", path, response.statusCode(), response.body());
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Supabase PATCH request failed", e);
        }
    }

    private JsonNode parseResponse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Supabase response", e);
        }
    }

    // ========== Initialization ==========

    private void initializeTables() {
        log.info("[Supabase] Initializing tables via SQL RPC");

        String createMessages = 
            "CREATE TABLE IF NOT EXISTS " + prefixed("messages") + " (" +
            "  id TEXT PRIMARY KEY," +
            "  conversation_id TEXT NOT NULL," +
            "  sender_id TEXT NOT NULL," +
            "  sender_email TEXT," +
            "  sender_name TEXT," +
            "  receiver_id TEXT NOT NULL," +
            "  receiver_email TEXT," +
            "  content TEXT NOT NULL," +
            "  message_type TEXT DEFAULT 'TEXT'," +
            "  media_url TEXT," +
            "  media_mime_type TEXT," +
            "  media_size BIGINT," +
            "  is_read BOOLEAN DEFAULT false," +
            "  read_at TIMESTAMPTZ," +
            "  status TEXT DEFAULT 'SENT'," +
            "  is_deleted BOOLEAN DEFAULT false," +
            "  created_at TIMESTAMPTZ DEFAULT now()," +
            "  updated_at TIMESTAMPTZ DEFAULT now()," +
            "  metadata JSONB," +
            "  tenant_id TEXT" +
            ");" +
            "CREATE INDEX IF NOT EXISTS idx_msg_conv ON " + prefixed("messages") + "(conversation_id);" +
            "CREATE INDEX IF NOT EXISTS idx_msg_sender ON " + prefixed("messages") + "(sender_id);" +
            "CREATE INDEX IF NOT EXISTS idx_msg_receiver ON " + prefixed("messages") + "(receiver_id);" +
            "CREATE INDEX IF NOT EXISTS idx_msg_unread ON " + prefixed("messages") + "(receiver_id, is_read);";

        String createConversations =
            "CREATE TABLE IF NOT EXISTS " + prefixed("conversations") + " (" +
            "  id TEXT PRIMARY KEY," +
            "  user1_id TEXT NOT NULL," +
            "  user1_email TEXT," +
            "  user2_id TEXT NOT NULL," +
            "  user2_email TEXT," +
            "  last_message TEXT," +
            "  last_message_time TIMESTAMPTZ," +
            "  status TEXT DEFAULT 'INIT'," +
            "  blocked_by_user1 BOOLEAN DEFAULT false," +
            "  blocked_by_user2 BOOLEAN DEFAULT false," +
            "  message_count BIGINT DEFAULT 0," +
            "  unread_count_user1 INT DEFAULT 0," +
            "  unread_count_user2 INT DEFAULT 0," +
            "  created_at TIMESTAMPTZ DEFAULT now()," +
            "  updated_at TIMESTAMPTZ DEFAULT now()," +
            "  metadata JSONB," +
            "  tenant_id TEXT," +
            "  UNIQUE (user1_id, user2_id)" +
            ");" +
            "CREATE INDEX IF NOT EXISTS idx_conv_user1 ON " + prefixed("conversations") + "(user1_id);" +
            "CREATE INDEX IF NOT EXISTS idx_conv_user2 ON " + prefixed("conversations") + "(user2_id);";

        try {
            // Execute SQL via Supabase SQL RPC endpoint
            ObjectNode sqlBody = objectMapper.createObjectNode();
            sqlBody.put("query", createMessages + createConversations);
            postRequest("/rest/v1/rpc/exec_sql", sqlBody.toString());
            log.info("[Supabase] Tables initialized successfully");
        } catch (Exception e) {
            log.warn("[Supabase] Could not auto-create tables via RPC. " +
                    "Please ensure the tables exist. Error: {}", e.getMessage());
        }
    }

    // ========== JSON Mapping ==========

    private List<ChatMessageData> parseMessageList(String response) {
        JsonNode nodes = parseResponse(response);
        List<ChatMessageData> messages = new ArrayList<>();
        if (nodes.isArray()) {
            for (JsonNode node : nodes) {
                messages.add(jsonToMessage(node));
            }
        }
        return messages;
    }

    private ChatMessageData jsonToMessage(JsonNode node) {
        return ChatMessageData.builder()
                .id(node.path("id").asText(null))
                .conversationId(node.path("conversation_id").asText(null))
                .senderId(node.path("sender_id").asText(null))
                .senderEmail(node.path("sender_email").asText(null))
                .senderName(node.path("sender_name").asText(null))
                .receiverId(node.path("receiver_id").asText(null))
                .receiverEmail(node.path("receiver_email").asText(null))
                .content(node.path("content").asText(null))
                .messageType(node.path("message_type").asText("TEXT"))
                .mediaUrl(node.path("media_url").asText(null))
                .mediaMimeType(node.path("media_mime_type").asText(null))
                .mediaSize(node.path("media_size").isNull() ? null : node.path("media_size").asLong())
                .read(node.path("is_read").asBoolean(false))
                .readAt(node.path("read_at").isNull() ? null : Instant.parse(node.path("read_at").asText()))
                .status(node.path("status").asText("SENT"))
                .deleted(node.path("is_deleted").asBoolean(false))
                .createdAt(node.path("created_at").isNull() ? null : Instant.parse(node.path("created_at").asText()))
                .updatedAt(node.path("updated_at").isNull() ? null : Instant.parse(node.path("updated_at").asText()))
                .tenantId(node.path("tenant_id").asText(null))
                .build();
    }

    private ConversationData jsonToConversation(JsonNode node) {
        return ConversationData.builder()
                .id(node.path("id").asText(null))
                .user1Id(node.path("user1_id").asText(null))
                .user1Email(node.path("user1_email").asText(null))
                .user2Id(node.path("user2_id").asText(null))
                .user2Email(node.path("user2_email").asText(null))
                .lastMessage(node.path("last_message").asText(null))
                .lastMessageTime(node.path("last_message_time").isNull() ? null :
                        Instant.parse(node.path("last_message_time").asText()))
                .status(node.path("status").asText("INIT"))
                .blockedByUser1(node.path("blocked_by_user1").asBoolean(false))
                .blockedByUser2(node.path("blocked_by_user2").asBoolean(false))
                .messageCount(node.path("message_count").asLong(0))
                .unreadCountUser1(node.path("unread_count_user1").asInt(0))
                .unreadCountUser2(node.path("unread_count_user2").asInt(0))
                .createdAt(node.path("created_at").isNull() ? null :
                        Instant.parse(node.path("created_at").asText()))
                .updatedAt(node.path("updated_at").isNull() ? null :
                        Instant.parse(node.path("updated_at").asText()))
                .tenantId(node.path("tenant_id").asText(null))
                .build();
    }
}
