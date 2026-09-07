package com.ayush.chat.storage.provider;

import com.ayush.chat.storage.StorageType;
import com.ayush.chat.storage.config.ChatStorageProperties;
import com.ayush.chat.storage.dto.ChatMessageData;
import com.ayush.chat.storage.dto.ConversationData;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * SQL-based chat storage provider.
 * 
 * Works with MySQL, PostgreSQL, H2, and any other JDBC-compatible database.
 * Uses raw JDBC for maximum compatibility without JPA/Hibernate dependencies.
 * 
 * Table schema is auto-created on initialization:
 * - chat_messages: Stores all chat messages
 * - chat_conversations: Stores all conversations
 * - chat_participants: Stores conversation participants (for future use)
 */
@Slf4j
public class SqlChatStorageProvider extends AbstractChatStorageProvider {

    private final DataSource dataSource;
    private final StorageType storageType;

    public SqlChatStorageProvider(ChatStorageProperties properties, StorageType storageType) {
        super(properties);
        this.storageType = storageType;
        this.dataSource = createDataSource(properties, storageType);
        initializeSchema();
    }

    /**
     * Constructor for externally-provided DataSource (e.g., from dynamic routing).
     */
    public SqlChatStorageProvider(ChatStorageProperties properties, StorageType storageType, DataSource dataSource) {
        super(properties);
        this.storageType = storageType;
        this.dataSource = dataSource;
        initializeSchema();
    }

    @Override
    public StorageType getStorageType() {
        return storageType;
    }

    @Override
    public boolean isHealthy() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(5);
        } catch (SQLException e) {
            log.error("[SQL] Health check failed", e);
            return false;
        }
    }

    // ========== Message Operations ==========

    @Override
    protected ChatMessageData doSaveMessage(ChatMessageData message) {
        String sql = "INSERT INTO " + prefixed("messages") + 
                " (id, conversation_id, sender_id, sender_email, sender_name, " +
                "receiver_id, receiver_email, content, message_type, media_url, " +
                "media_mime_type, media_size, is_read, read_at, status, is_deleted, " +
                "created_at, updated_at, metadata, tenant_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "content = VALUES(content), status = VALUES(status), " +
                "is_read = VALUES(is_read), updated_at = VALUES(updated_at)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, message.getId());
            ps.setString(2, message.getConversationId());
            ps.setString(3, message.getSenderId());
            ps.setString(4, message.getSenderEmail());
            ps.setString(5, message.getSenderName());
            ps.setString(6, message.getReceiverId());
            ps.setString(7, message.getReceiverEmail());
            ps.setString(8, message.getContent());
            ps.setString(9, message.getMessageType());
            ps.setString(10, message.getMediaUrl());
            ps.setString(11, message.getMediaMimeType());
            ps.setObject(12, message.getMediaSize());
            ps.setBoolean(13, message.isRead());
            ps.setTimestamp(14, message.getReadAt() != null ? 
                    Timestamp.from(message.getReadAt()) : null);
            ps.setString(15, message.getStatus());
            ps.setBoolean(16, message.isDeleted());
            ps.setTimestamp(17, message.getCreatedAt() != null ? 
                    Timestamp.from(message.getCreatedAt()) : null);
            ps.setTimestamp(18, message.getUpdatedAt() != null ? 
                    Timestamp.from(message.getUpdatedAt()) : null);
            ps.setString(19, message.getMetadata() != null ? 
                    message.getMetadata().toString() : null);
            ps.setString(20, message.getTenantId());

            ps.executeUpdate();
            return message;

        } catch (SQLException e) {
            log.error("[SQL] Failed to save message", e);
            throw new RuntimeException("Failed to save message", e);
        }
    }

    @Override
    public List<ChatMessageData> saveMessages(List<ChatMessageData> messages) {
        String sql = "INSERT INTO " + prefixed("messages") +
                " (id, conversation_id, sender_id, sender_email, sender_name, " +
                "receiver_id, receiver_email, content, message_type, media_url, " +
                "media_mime_type, media_size, is_read, read_at, status, is_deleted, " +
                "created_at, updated_at, metadata, tenant_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false);
            for (ChatMessageData m : messages) {
                if (m.getId() == null) m.setId(generateId());
                if (m.getCreatedAt() == null) m.setCreatedAt(Instant.now());
                m.setUpdatedAt(Instant.now());

                ps.setString(1, m.getId());
                ps.setString(2, m.getConversationId());
                ps.setString(3, m.getSenderId());
                ps.setString(4, m.getSenderEmail());
                ps.setString(5, m.getSenderName());
                ps.setString(6, m.getReceiverId());
                ps.setString(7, m.getReceiverEmail());
                ps.setString(8, m.getContent());
                ps.setString(9, m.getMessageType());
                ps.setString(10, m.getMediaUrl());
                ps.setString(11, m.getMediaMimeType());
                ps.setObject(12, m.getMediaSize());
                ps.setBoolean(13, m.isRead());
                ps.setTimestamp(14, m.getReadAt() != null ? 
                        Timestamp.from(m.getReadAt()) : null);
                ps.setString(15, m.getStatus());
                ps.setBoolean(16, m.isDeleted());
                ps.setTimestamp(17, Timestamp.from(m.getCreatedAt()));
                ps.setTimestamp(18, Timestamp.from(m.getUpdatedAt()));
                ps.setString(19, m.getMetadata() != null ? 
                        m.getMetadata().toString() : null);
                ps.setString(20, m.getTenantId());

                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
            return messages;

        } catch (SQLException e) {
            log.error("[SQL] Failed to batch save messages", e);
            throw new RuntimeException("Failed to batch save messages", e);
        }
    }

    @Override
    public Optional<ChatMessageData> findMessageById(String messageId) {
        String sql = "SELECT * FROM " + prefixed("messages") + " WHERE id = ? AND is_deleted = false";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, messageId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapMessage(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find message", e);
        }
    }

    @Override
    public List<ChatMessageData> findMessagesByConversation(String conversationId) {
        String sql = "SELECT * FROM " + prefixed("messages") +
                " WHERE conversation_id = ? AND is_deleted = false ORDER BY created_at ASC";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ResultSet rs = ps.executeQuery();
            List<ChatMessageData> messages = new ArrayList<>();
            while (rs.next()) {
                messages.add(mapMessage(rs));
            }
            return messages;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find messages", e);
        }
    }

    @Override
    public List<ChatMessageData> findMessagesByConversation(String conversationId, int offset, int limit) {
        String sql = "SELECT * FROM " + prefixed("messages") +
                " WHERE conversation_id = ? AND is_deleted = false ORDER BY created_at ASC LIMIT ? OFFSET ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            ResultSet rs = ps.executeQuery();
            List<ChatMessageData> messages = new ArrayList<>();
            while (rs.next()) {
                messages.add(mapMessage(rs));
            }
            return messages;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find messages", e);
        }
    }

    @Override
    public List<ChatMessageData> findMessagesSince(String conversationId, Instant since) {
        String sql = "SELECT * FROM " + prefixed("messages") +
                " WHERE conversation_id = ? AND created_at > ? AND is_deleted = false ORDER BY created_at ASC";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.setTimestamp(2, Timestamp.from(since));
            ResultSet rs = ps.executeQuery();
            List<ChatMessageData> messages = new ArrayList<>();
            while (rs.next()) {
                messages.add(mapMessage(rs));
            }
            return messages;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find messages", e);
        }
    }

    @Override
    public List<ChatMessageData> findUnreadMessages(String userId) {
        String sql = "SELECT * FROM " + prefixed("messages") +
                " WHERE receiver_id = ? AND is_read = false AND is_deleted = false ORDER BY created_at ASC";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ResultSet rs = ps.executeQuery();
            List<ChatMessageData> messages = new ArrayList<>();
            while (rs.next()) {
                messages.add(mapMessage(rs));
            }
            return messages;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find unread messages", e);
        }
    }

    @Override
    public List<ChatMessageData> findUnreadMessages(String senderId, String receiverId) {
        String sql = "SELECT * FROM " + prefixed("messages") +
                " WHERE sender_id = ? AND receiver_id = ? AND is_read = false AND is_deleted = false ORDER BY created_at ASC";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, senderId);
            ps.setString(2, receiverId);
            ResultSet rs = ps.executeQuery();
            List<ChatMessageData> messages = new ArrayList<>();
            while (rs.next()) {
                messages.add(mapMessage(rs));
            }
            return messages;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find unread messages", e);
        }
    }

    @Override
    protected void doMarkMessagesAsRead(List<String> messageIds) {
        String placeholders = String.join(",", Collections.nCopies(messageIds.size(), "?"));
        String sql = "UPDATE " + prefixed("messages") +
                " SET is_read = true, read_at = NOW(), updated_at = NOW() WHERE id IN (" + placeholders + ")";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < messageIds.size(); i++) {
                ps.setString(i + 1, messageIds.get(i));
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark messages as read", e);
        }
    }

    @Override
    public void updateMessageStatus(String messageId, String status) {
        String sql = "UPDATE " + prefixed("messages") + " SET status = ?, updated_at = NOW() WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update message status", e);
        }
    }

    @Override
    protected void doDeleteMessage(String messageId) {
        String sql = "UPDATE " + prefixed("messages") + " SET is_deleted = true, updated_at = NOW() WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete message", e);
        }
    }

    // ========== Conversation Operations ==========

    @Override
    protected ConversationData doSaveConversation(ConversationData conv) {
        String sql = "INSERT INTO " + prefixed("conversations") +
                " (id, user1_id, user1_email, user2_id, user2_email, last_message, " +
                "last_message_time, status, blocked_by_user1, blocked_by_user2, " +
                "message_count, unread_count_user1, unread_count_user2, " +
                "created_at, updated_at, metadata, tenant_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "status = VALUES(status), last_message = VALUES(last_message), " +
                "last_message_time = VALUES(last_message_time), updated_at = VALUES(updated_at)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, conv.getId());
            ps.setString(2, conv.getUser1Id());
            ps.setString(3, conv.getUser1Email());
            ps.setString(4, conv.getUser2Id());
            ps.setString(5, conv.getUser2Email());
            ps.setString(6, conv.getLastMessage());
            ps.setTimestamp(7, conv.getLastMessageTime() != null ? 
                    Timestamp.from(conv.getLastMessageTime()) : null);
            ps.setString(8, conv.getStatus());
            ps.setBoolean(9, conv.isBlockedByUser1());
            ps.setBoolean(10, conv.isBlockedByUser2());
            ps.setLong(11, conv.getMessageCount());
            ps.setInt(12, conv.getUnreadCountUser1());
            ps.setInt(13, conv.getUnreadCountUser2());
            ps.setTimestamp(14, conv.getCreatedAt() != null ? 
                    Timestamp.from(conv.getCreatedAt()) : null);
            ps.setTimestamp(15, conv.getUpdatedAt() != null ? 
                    Timestamp.from(conv.getUpdatedAt()) : null);
            ps.setString(16, conv.getMetadata() != null ? 
                    conv.getMetadata().toString() : null);
            ps.setString(17, conv.getTenantId());

            ps.executeUpdate();
            return conv;

        } catch (SQLException e) {
            log.error("[SQL] Failed to save conversation", e);
            throw new RuntimeException("Failed to save conversation", e);
        }
    }

    @Override
    public Optional<ConversationData> findConversationById(String conversationId) {
        String sql = "SELECT * FROM " + prefixed("conversations") + " WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapConversation(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find conversation", e);
        }
    }

    @Override
    public Optional<ConversationData> findConversationByUsers(String user1Id, String user2Id) {
        String sql = "SELECT * FROM " + prefixed("conversations") +
                " WHERE (user1_id = ? AND user2_id = ?) OR (user1_id = ? AND user2_id = ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, user1Id);
            ps.setString(2, user2Id);
            ps.setString(3, user2Id);
            ps.setString(4, user1Id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapConversation(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find conversation", e);
        }
    }

    @Override
    public List<ConversationData> findConversationsByUser(String userId) {
        String sql = "SELECT * FROM " + prefixed("conversations") +
                " WHERE (user1_id = ? OR user2_id = ?) ORDER BY last_message_time DESC NULLS LAST";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, userId);
            ResultSet rs = ps.executeQuery();
            List<ConversationData> conversations = new ArrayList<>();
            while (rs.next()) {
                conversations.add(mapConversation(rs));
            }
            return conversations;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find conversations", e);
        }
    }

    @Override
    public void updateConversationLastMessage(String conversationId, String lastMessage, Instant lastMessageTime) {
        String sql = "UPDATE " + prefixed("conversations") +
                " SET last_message = ?, last_message_time = ?, message_count = message_count + 1, updated_at = NOW() WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, lastMessage);
            ps.setTimestamp(2, Timestamp.from(lastMessageTime));
            ps.setString(3, conversationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update conversation", e);
        }
    }

    @Override
    public void updateConversationStatus(String conversationId, String status) {
        String sql = "UPDATE " + prefixed("conversations") + " SET status = ?, updated_at = NOW() WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, conversationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update conversation status", e);
        }
    }

    @Override
    public void setConversationBlock(String conversationId, String userId, boolean blocked) {
        // We need to determine which user column to update
        Optional<ConversationData> conv = findConversationById(conversationId);
        if (conv.isEmpty()) return;

        ConversationData c = conv.get();
        String sql;
        if (userId.equals(c.getUser1Id())) {
            sql = "UPDATE " + prefixed("conversations") +
                    " SET blocked_by_user1 = ?, status = CASE WHEN ? = true THEN 'INACTIVE' ELSE 'ACTIVE' END, updated_at = NOW() WHERE id = ?";
        } else {
            sql = "UPDATE " + prefixed("conversations") +
                    " SET blocked_by_user2 = ?, status = CASE WHEN ? = true THEN 'INACTIVE' ELSE 'ACTIVE' END, updated_at = NOW() WHERE id = ?";
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, blocked);
            ps.setBoolean(2, blocked);
            ps.setString(3, conversationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update conversation block", e);
        }
    }

    @Override
    protected void doDeleteConversation(String conversationId) {
        String sql = "UPDATE " + prefixed("conversations") +
                " SET status = 'INACTIVE', updated_at = NOW() WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete conversation", e);
        }
    }

    // ========== Private Helpers ==========

    private DataSource createDataSource(ChatStorageProperties props, StorageType type) {
        ChatStorageProperties.SqlConfig sqlConfig = props.getSql();

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("chat-storage-" + type.getCode());
        hikariConfig.setJdbcUrl(sqlConfig.getUrl());
        hikariConfig.setUsername(sqlConfig.getUsername());
        hikariConfig.setPassword(sqlConfig.getPassword());
        hikariConfig.setMaximumPoolSize(sqlConfig.getPoolSize());
        hikariConfig.setMinimumIdle(sqlConfig.getMinIdle());
        hikariConfig.setConnectionTimeout(sqlConfig.getConnectionTimeout());

        if (sqlConfig.getDriverClassName() != null) {
            hikariConfig.setDriverClassName(sqlConfig.getDriverClassName());
        }

        log.info("[SQL] Creating DataSource for {}: url={}", type.getDisplayName(), sqlConfig.getUrl());
        return new HikariDataSource(hikariConfig);
    }

    private void initializeSchema() {
        log.info("[SQL] Initializing schema for {}", storageType.getDisplayName());
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Create messages table
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + prefixed("messages") + " (" +
                "  id VARCHAR(64) PRIMARY KEY," +
                "  conversation_id VARCHAR(64) NOT NULL," +
                "  sender_id VARCHAR(64) NOT NULL," +
                "  sender_email VARCHAR(255)," +
                "  sender_name VARCHAR(255)," +
                "  receiver_id VARCHAR(64) NOT NULL," +
                "  receiver_email VARCHAR(255)," +
                "  content TEXT NOT NULL," +
                "  message_type VARCHAR(32) DEFAULT 'TEXT'," +
                "  media_url TEXT," +
                "  media_mime_type VARCHAR(128)," +
                "  media_size BIGINT," +
                "  is_read BOOLEAN DEFAULT false," +
                "  read_at TIMESTAMP," +
                "  status VARCHAR(32) DEFAULT 'SENT'," +
                "  is_deleted BOOLEAN DEFAULT false," +
                "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "  metadata TEXT," +
                "  tenant_id VARCHAR(64)," +
                "  INDEX idx_messages_conversation (conversation_id)," +
                "  INDEX idx_messages_sender (sender_id)," +
                "  INDEX idx_messages_receiver (receiver_id)," +
                "  INDEX idx_messages_unread (receiver_id, is_read)," +
                "  INDEX idx_messages_created (created_at)" +
                ")"
            );

            // Create conversations table
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + prefixed("conversations") + " (" +
                "  id VARCHAR(64) PRIMARY KEY," +
                "  user1_id VARCHAR(64) NOT NULL," +
                "  user1_email VARCHAR(255)," +
                "  user2_id VARCHAR(64) NOT NULL," +
                "  user2_email VARCHAR(255)," +
                "  last_message TEXT," +
                "  last_message_time TIMESTAMP," +
                "  status VARCHAR(32) DEFAULT 'INIT'," +
                "  blocked_by_user1 BOOLEAN DEFAULT false," +
                "  blocked_by_user2 BOOLEAN DEFAULT false," +
                "  message_count BIGINT DEFAULT 0," +
                "  unread_count_user1 INT DEFAULT 0," +
                "  unread_count_user2 INT DEFAULT 0," +
                "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "  metadata TEXT," +
                "  tenant_id VARCHAR(64)," +
                "  UNIQUE INDEX idx_conv_user_pair (user1_id, user2_id)," +
                "  INDEX idx_conv_user1 (user1_id)," +
                "  INDEX idx_conv_user2 (user2_id)" +
                ")"
            );

            log.info("[SQL] Schema initialized successfully for {}", storageType.getDisplayName());

        } catch (SQLException e) {
            log.error("[SQL] Failed to initialize schema", e);
            throw new RuntimeException("Failed to initialize SQL schema", e);
        }
    }

    private ChatMessageData mapMessage(ResultSet rs) throws SQLException {
        return ChatMessageData.builder()
                .id(rs.getString("id"))
                .conversationId(rs.getString("conversation_id"))
                .senderId(rs.getString("sender_id"))
                .senderEmail(rs.getString("sender_email"))
                .senderName(rs.getString("sender_name"))
                .receiverId(rs.getString("receiver_id"))
                .receiverEmail(rs.getString("receiver_email"))
                .content(rs.getString("content"))
                .messageType(rs.getString("message_type"))
                .mediaUrl(rs.getString("media_url"))
                .mediaMimeType(rs.getString("media_mime_type"))
                .mediaSize(rs.getObject("media_size") != null ? rs.getLong("media_size") : null)
                .read(rs.getBoolean("is_read"))
                .readAt(rs.getTimestamp("read_at") != null ? 
                        rs.getTimestamp("read_at").toInstant() : null)
                .status(rs.getString("status"))
                .deleted(rs.getBoolean("is_deleted"))
                .createdAt(rs.getTimestamp("created_at") != null ? 
                        rs.getTimestamp("created_at").toInstant() : null)
                .updatedAt(rs.getTimestamp("updated_at") != null ? 
                        rs.getTimestamp("updated_at").toInstant() : null)
                .tenantId(rs.getString("tenant_id"))
                .build();
    }

    private ConversationData mapConversation(ResultSet rs) throws SQLException {
        return ConversationData.builder()
                .id(rs.getString("id"))
                .user1Id(rs.getString("user1_id"))
                .user1Email(rs.getString("user1_email"))
                .user2Id(rs.getString("user2_id"))
                .user2Email(rs.getString("user2_email"))
                .lastMessage(rs.getString("last_message"))
                .lastMessageTime(rs.getTimestamp("last_message_time") != null ?
                        rs.getTimestamp("last_message_time").toInstant() : null)
                .status(rs.getString("status"))
                .blockedByUser1(rs.getBoolean("blocked_by_user1"))
                .blockedByUser2(rs.getBoolean("blocked_by_user2"))
                .messageCount(rs.getLong("message_count"))
                .unreadCountUser1(rs.getInt("unread_count_user1"))
                .unreadCountUser2(rs.getInt("unread_count_user2"))
                .createdAt(rs.getTimestamp("created_at") != null ?
                        rs.getTimestamp("created_at").toInstant() : null)
                .updatedAt(rs.getTimestamp("updated_at") != null ?
                        rs.getTimestamp("updated_at").toInstant() : null)
                .tenantId(rs.getString("tenant_id"))
                .build();
    }
}
