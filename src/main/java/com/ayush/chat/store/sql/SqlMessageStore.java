package com.ayush.chat.store.sql;

import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.exception.DomainException;
import com.ayush.chat.core.exception.ErrorCode;
import com.ayush.chat.core.exception.InfrastructureUnavailableException;
import com.ayush.chat.core.util.Json;
import com.ayush.chat.store.MessagePreview;
import com.ayush.chat.store.MessageStore;
import com.ayush.chat.store.StoreSupport;
import com.ayush.chat.store.UserSearch;
import com.ayush.chat.store.model.AppendResult;
import com.ayush.chat.store.model.ChatMessage;
import com.ayush.chat.store.model.ChatUser;
import com.ayush.chat.store.model.Conversation;
import com.ayush.chat.store.model.FieldLimits;
import com.ayush.chat.store.model.InboxItem;
import com.ayush.chat.store.model.Participant;
import com.ayush.chat.store.model.StoreStats;
import com.fasterxml.jackson.core.type.TypeReference;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * {@link MessageStore} over JDBC for H2, PostgreSQL-compatible and MySQL-compatible databases.
 *
 * <p>Sequence allocation takes the conversation row lock ({@code UPDATE ... last_seq = last_seq + 1})
 * inside the append transaction, so concurrent senders in one conversation are serialised while
 * different conversations proceed in parallel. Idempotency is backed by a unique index on
 * (tenant, conversation, sender, client_msg_id).
 *
 * <p>Database failures surface as {@link InfrastructureUnavailableException} (503). Unique-key
 * violations are expected outcomes of races and are resolved inside the store.
 */
@Slf4j
public class SqlMessageStore implements MessageStore {

    private static final String COMPONENT = ConnectorKind.MESSAGE_STORE.getDisplayName();
    private static final Pattern SAFE_PREFIX = Pattern.compile("[A-Za-z0-9_]{0,32}");
    private static final int MAX_RETRIES = 5;
    /** Width of columns holding enum names (conversation and message type, participant role). */
    private static final int ENUM_NAME = 16;
    private static final TypeReference<Set<String>> STRING_SET = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Set<String>>> REACTIONS = new TypeReference<>() {
    };

    private final String providerId;
    private final HikariDataSource dataSource;
    private final SqlDialect dialect;
    private final String users;
    private final String blocks;
    private final String conversations;
    private final String participants;
    private final String messages;
    private final String prefix;

    /** @param tablePrefix prepended to every table and constraint name; null for none */
    public SqlMessageStore(String providerId, HikariDataSource dataSource, String tablePrefix) {
        String p = tablePrefix == null ? "" : tablePrefix;
        if (!SAFE_PREFIX.matcher(p).matches()) {
            throw new IllegalArgumentException("Table prefix may only contain letters, digits and underscores");
        }
        this.providerId = providerId;
        this.dataSource = dataSource;
        this.dialect = SqlDialect.fromJdbcUrl(dataSource.getJdbcUrl());
        this.prefix = p;
        this.users = p + "users";
        this.blocks = p + "blocks";
        this.conversations = p + "conversations";
        this.participants = p + "participants";
        this.messages = p + "messages";
        initializeSchema();
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public HealthStatus health() {
        try (Connection conn = dataSource.getConnection()) {
            if (!conn.isValid(3)) {
                return HealthStatus.down("Connection invalid");
            }
            var pool = dataSource.getHikariPoolMXBean();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("dialect", dialect.name());
            details.put("database", conn.getMetaData().getDatabaseProductName() + " "
                    + conn.getMetaData().getDatabaseProductVersion());
            if (pool != null) {
                details.put("activeConnections", pool.getActiveConnections());
                details.put("idleConnections", pool.getIdleConnections());
            }
            return HealthStatus.up("Connected", details);
        } catch (SQLException e) {
            return HealthStatus.down(e.getMessage());
        }
    }

    @Override
    public void close() {
        if (!dataSource.isClosed()) {
            dataSource.close();
        }
    }

    // ================================================================== schema

    private void initializeSchema() {
        String text = dialect.textType();
        String tenantId = "tenant_id " + varchar(FieldLimits.TENANT_ID) + " NOT NULL";
        String userId = varchar(FieldLimits.USER_ID);
        String entityId = varchar(FieldLimits.ENTITY_ID);
        String enumName = varchar(ENUM_NAME);
        List<String> ddl = List.of(
                "CREATE TABLE IF NOT EXISTS " + users + " ("
                        + tenantId + ", id " + userId + " NOT NULL, display_name " + varchar(FieldLimits.DISPLAY_NAME) + ","
                        + " email " + varchar(FieldLimits.EMAIL) + ", phone " + varchar(FieldLimits.PHONE) + ","
                        + " avatar_url " + varchar(FieldLimits.URL) + ", about " + varchar(FieldLimits.ABOUT) + ","
                        + " push_tokens " + text + ", metadata " + text + ", deactivated BOOLEAN NOT NULL,"
                        + " created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, last_seen_at BIGINT,"
                        + " PRIMARY KEY (tenant_id, id))",
                "CREATE TABLE IF NOT EXISTS " + blocks + " ("
                        + tenantId + ", blocker_id " + userId + " NOT NULL, blocked_id " + userId + " NOT NULL,"
                        + " created_at BIGINT NOT NULL, PRIMARY KEY (tenant_id, blocker_id, blocked_id))",
                "CREATE TABLE IF NOT EXISTS " + conversations + " ("
                        + tenantId + ", id " + entityId + " NOT NULL, type " + enumName + " NOT NULL,"
                        + " direct_key " + varchar(FieldLimits.DIRECT_KEY) + ", title " + varchar(FieldLimits.TITLE) + ","
                        + " description " + varchar(FieldLimits.DESCRIPTION) + ", avatar_url " + varchar(FieldLimits.URL) + ","
                        + " created_by " + userId + ", last_seq BIGINT NOT NULL, last_message_id " + entityId + ","
                        + " last_message_preview " + varchar(FieldLimits.PREVIEW) + ", last_message_sender_id " + userId + ","
                        + " last_message_at BIGINT,"
                        + " activity_at BIGINT NOT NULL, metadata " + text + ", created_at BIGINT NOT NULL,"
                        + " updated_at BIGINT NOT NULL, PRIMARY KEY (tenant_id, id),"
                        + " CONSTRAINT " + prefix + "uq_conv_direct UNIQUE (tenant_id, direct_key))",
                "CREATE TABLE IF NOT EXISTS " + participants + " ("
                        + tenantId + ", conversation_id " + entityId + " NOT NULL, user_id " + userId + " NOT NULL,"
                        + " role " + enumName + " NOT NULL, joined_seq BIGINT NOT NULL, delivered_seq BIGINT NOT NULL,"
                        + " read_seq BIGINT NOT NULL, muted BOOLEAN NOT NULL, archived BOOLEAN NOT NULL, pinned BOOLEAN NOT NULL,"
                        + " joined_at BIGINT, left_at BIGINT, updated_at BIGINT,"
                        + " PRIMARY KEY (tenant_id, conversation_id, user_id))",
                "CREATE TABLE IF NOT EXISTS " + messages + " ("
                        + tenantId + ", conversation_id " + entityId + " NOT NULL, seq BIGINT NOT NULL,"
                        + " id " + entityId + " NOT NULL, sender_id " + userId + " NOT NULL,"
                        + " client_msg_id " + varchar(FieldLimits.CLIENT_MSG_ID) + ","
                        + " type " + enumName + " NOT NULL, content " + text + ", attachment " + text + ","
                        + " reply_to_id " + entityId + ","
                        + " forwarded BOOLEAN NOT NULL, reactions " + text + ", edited BOOLEAN NOT NULL, edited_at BIGINT,"
                        + " deleted BOOLEAN NOT NULL, deleted_at BIGINT, metadata " + text + ", created_at BIGINT NOT NULL,"
                        + " PRIMARY KEY (tenant_id, conversation_id, seq),"
                        + " CONSTRAINT " + prefix + "uq_msg_id UNIQUE (tenant_id, conversation_id, id),"
                        + " CONSTRAINT " + prefix + "uq_msg_client UNIQUE (tenant_id, conversation_id, sender_id, client_msg_id))");
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            for (String sql : ddl) {
                stmt.execute(sql);
            }
            createIndex(stmt, prefix + "idx_users_email", users, "tenant_id, email");
            createIndex(stmt, prefix + "idx_conv_activity", conversations, "tenant_id, activity_at");
            createIndex(stmt, prefix + "idx_part_user", participants, "tenant_id, user_id");
            log.info("[{}] schema ready ({} tables with prefix '{}')", providerId, dialect, prefix);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialise schema: " + e.getMessage(), e);
        }
    }

    private static String varchar(int length) {
        return "VARCHAR(" + length + ")";
    }

    private void createIndex(Statement stmt, String name, String table, String columns) throws SQLException {
        if (dialect.supportsCreateIndexIfNotExists()) {
            stmt.execute("CREATE INDEX IF NOT EXISTS " + name + " ON " + table + " (" + columns + ")");
            return;
        }
        try {
            stmt.execute("CREATE INDEX " + name + " ON " + table + " (" + columns + ")");
        } catch (SQLException e) {
            if (!SqlDialect.isAlreadyExists(e)) {
                throw e;
            }
        }
    }

    // ================================================================== jdbc plumbing

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection conn) throws SQLException;
    }

    /** Runs auto-committed statements on one pooled connection. */
    private <T> T read(SqlWork<T> work) {
        try (Connection conn = dataSource.getConnection()) {
            return work.run(conn);
        } catch (SQLException e) {
            throw new InfrastructureUnavailableException(COMPONENT, e);
        }
    }

    /**
     * Runs the work in one transaction, retrying serialization failures and deadlocks. A unique-key
     * violation rolls back and surfaces as {@link UniqueViolationException} for the caller to
     * resolve; any other database failure is an {@link InfrastructureUnavailableException}.
     */
    private <T> T transaction(SqlWork<T> work) {
        for (int attempt = 1; ; attempt++) {
            try (Connection conn = dataSource.getConnection()) {
                boolean autoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    T result = work.run(conn);
                    conn.commit();
                    return result;
                } catch (SQLException | RuntimeException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(autoCommit);
                }
            } catch (SQLException e) {
                if (SqlDialect.isRetryable(e) && attempt < MAX_RETRIES) {
                    log.debug("[{}] retrying transaction after {}", providerId, e.getSQLState());
                    continue;
                }
                if (SqlDialect.isUniqueViolation(e)) {
                    throw new UniqueViolationException(e);
                }
                throw new InfrastructureUnavailableException(COMPONENT, e);
            }
        }
    }

    private static int update(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            int idx = i + 1;
            if (p == null) {
                ps.setNull(idx, Types.VARCHAR);
            } else if (p instanceof NullLong) {
                ps.setNull(idx, Types.BIGINT);
            } else if (p instanceof String s) {
                ps.setString(idx, s);
            } else if (p instanceof Long l) {
                ps.setLong(idx, l);
            } else if (p instanceof Integer n) {
                ps.setInt(idx, n);
            } else if (p instanceof Boolean b) {
                ps.setBoolean(idx, b);
            } else {
                ps.setObject(idx, p);
            }
        }
    }

    /** Marker for a typed BIGINT null (some drivers reject VARCHAR-typed nulls for BIGINT columns). */
    private enum NullLong { INSTANCE }

    private static Object millis(Instant instant) {
        return instant == null ? NullLong.INSTANCE : instant.toEpochMilli();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(v);
    }

    private static <T> List<T> list(Connection conn, String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
                return out;
            }
        }
    }

    private static <T> Optional<T> one(Connection conn, String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        List<T> rows = list(conn, sql, mapper, params);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static long count(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    // ================================================================== users

    @Override
    public ChatUser upsertUser(ChatUser user) {
        try {
            return transaction(conn -> writeUser(conn, user));
        } catch (UniqueViolationException raced) {
            // A concurrent first upsert inserted the row between our UPDATE and INSERT: update it now.
            return retryAfterRace(() -> transaction(conn -> writeUser(conn, user)), "User");
        }
    }

    private ChatUser writeUser(Connection conn, ChatUser user) throws SQLException {
        Object[] values = {user.getDisplayName(), user.getEmail(), user.getPhone(), user.getAvatarUrl(), user.getAbout(),
                Json.write(user.getPushTokens()), Json.write(user.getMetadata()), user.isDeactivated(),
                millis(user.getUpdatedAt() != null ? user.getUpdatedAt() : Instant.now())};
        int updated = update(conn, "UPDATE " + users + " SET display_name = ?, email = ?, phone = ?, avatar_url = ?,"
                        + " about = ?, push_tokens = ?, metadata = ?, deactivated = ?, updated_at = ?"
                        + " WHERE tenant_id = ? AND id = ?",
                StoreSupport.concat(values, user.getTenantId(), user.getId()));
        if (updated == 0) {
            Instant created = user.getCreatedAt() != null ? user.getCreatedAt() : Instant.now();
            update(conn, "INSERT INTO " + users + " (display_name, email, phone, avatar_url, about, push_tokens, metadata,"
                            + " deactivated, updated_at, tenant_id, id, created_at, last_seen_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    StoreSupport.concat(values, user.getTenantId(), user.getId(), millis(created),
                            millis(user.getLastSeenAt())));
        }
        return one(conn, "SELECT * FROM " + users + " WHERE tenant_id = ? AND id = ?", this::mapUser,
                user.getTenantId(), user.getId()).orElseThrow();
    }

    /** Second attempt after losing a first-insert race; losing again is a genuine conflict. */
    private static <T> T retryAfterRace(Supplier<T> attempt, String what) {
        try {
            return attempt.get();
        } catch (UniqueViolationException e) {
            throw conflict(what, e);
        }
    }

    private static DomainException conflict(String what, UniqueViolationException cause) {
        return new DomainException(ErrorCode.CONFLICT, what + " conflicts with an existing record", cause);
    }

    @Override
    public Optional<ChatUser> findUser(String tenantId, String userId) {
        return read(conn -> one(conn, "SELECT * FROM " + users + " WHERE tenant_id = ? AND id = ?", this::mapUser,
                tenantId, userId));
    }

    @Override
    public List<ChatUser> findUsers(String tenantId, Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        List<String> ids = List.copyOf(new LinkedHashSet<>(userIds));
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Object[] params = StoreSupport.concat(new Object[]{tenantId}, ids.toArray());
        return read(conn -> list(conn, "SELECT * FROM " + users + " WHERE tenant_id = ? AND id IN (" + placeholders + ")",
                this::mapUser, params));
    }

    @Override
    public List<ChatUser> searchUsers(String tenantId, String query, int offset, int limit) {
        if (query == null || query.isBlank()) {
            return read(conn -> list(conn, "SELECT * FROM " + users + " WHERE tenant_id = ? ORDER BY id LIMIT ? OFFSET ?",
                    this::mapUser, tenantId, limit, offset));
        }
        String pattern = "%" + UserSearch.normalize(query).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return read(conn -> list(conn, "SELECT * FROM " + users + " WHERE tenant_id = ? AND (LOWER(id) LIKE ? ESCAPE '!'"
                        + " OR LOWER(display_name) LIKE ? ESCAPE '!' OR LOWER(email) LIKE ? ESCAPE '!')"
                        + " ORDER BY id LIMIT ? OFFSET ?",
                this::mapUser, tenantId, pattern, pattern, pattern, limit, offset));
    }

    @Override
    public long countUsers(String tenantId) {
        return read(conn -> count(conn, "SELECT COUNT(*) FROM " + users + " WHERE tenant_id = ?", tenantId));
    }

    @Override
    public void updateLastSeen(String tenantId, String userId, Instant lastSeenAt) {
        read(conn -> update(conn, "UPDATE " + users + " SET last_seen_at = ? WHERE tenant_id = ? AND id = ?",
                millis(lastSeenAt), tenantId, userId));
    }

    private ChatUser mapUser(ResultSet rs) throws SQLException {
        Set<String> tokens = Json.read(rs.getString("push_tokens"), STRING_SET);
        return ChatUser.builder()
                .tenantId(rs.getString("tenant_id"))
                .id(rs.getString("id"))
                .displayName(rs.getString("display_name"))
                .email(rs.getString("email"))
                .phone(rs.getString("phone"))
                .avatarUrl(rs.getString("avatar_url"))
                .about(rs.getString("about"))
                .pushTokens(tokens == null ? new LinkedHashSet<>() : new LinkedHashSet<>(tokens))
                .metadata(Json.readMap(rs.getString("metadata")))
                .deactivated(rs.getBoolean("deactivated"))
                .createdAt(instant(rs, "created_at"))
                .updatedAt(instant(rs, "updated_at"))
                .lastSeenAt(instant(rs, "last_seen_at"))
                .build();
    }

    // ================================================================== blocks

    @Override
    public void setBlocked(String tenantId, String blockerId, String blockedId, boolean blocked) {
        read(conn -> {
            if (!blocked) {
                return update(conn, "DELETE FROM " + blocks + " WHERE tenant_id = ? AND blocker_id = ? AND blocked_id = ?",
                        tenantId, blockerId, blockedId);
            }
            try {
                return update(conn, "INSERT INTO " + blocks + " (tenant_id, blocker_id, blocked_id, created_at) VALUES (?, ?, ?, ?)",
                        tenantId, blockerId, blockedId, System.currentTimeMillis());
            } catch (SQLException e) {
                if (SqlDialect.isUniqueViolation(e)) {
                    return 0;
                }
                throw e;
            }
        });
    }

    @Override
    public boolean isBlocked(String tenantId, String blockerId, String blockedId) {
        return read(conn -> count(conn, "SELECT COUNT(*) FROM " + blocks
                + " WHERE tenant_id = ? AND blocker_id = ? AND blocked_id = ?", tenantId, blockerId, blockedId) > 0);
    }

    @Override
    public List<String> listBlocked(String tenantId, String blockerId) {
        return read(conn -> list(conn, "SELECT blocked_id FROM " + blocks + " WHERE tenant_id = ? AND blocker_id = ?"
                + " ORDER BY blocked_id", rs -> rs.getString(1), tenantId, blockerId));
    }

    // ================================================================== conversations

    private static final String CONV_COLS = "tenant_id, id, type, direct_key, title, description, avatar_url, created_by,"
            + " last_seq, last_message_id, last_message_preview, last_message_sender_id, last_message_at, activity_at,"
            + " metadata, created_at, updated_at";
    private static final String PART_COLS = "tenant_id, conversation_id, user_id, role, joined_seq, delivered_seq, read_seq,"
            + " muted, archived, pinned, joined_at, left_at, updated_at";

    @Override
    public Conversation createConversation(Conversation conversation, List<Participant> initial) {
        if (conversation.getType() == Conversation.Type.DIRECT) {
            Optional<Conversation> existing = findDirectConversation(conversation.getTenantId(), conversation.getDirectKey());
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        try {
            return transaction(conn -> {
                update(conn, "INSERT INTO " + conversations + " (" + CONV_COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        conversation.getTenantId(), conversation.getId(), conversation.getType().name(),
                        conversation.getDirectKey(), conversation.getTitle(), conversation.getDescription(),
                        conversation.getAvatarUrl(), conversation.getCreatedBy(), conversation.getLastSeq(),
                        conversation.getLastMessageId(), conversation.getLastMessagePreview(),
                        conversation.getLastMessageSenderId(), millis(conversation.getLastMessageAt()),
                        millis(conversation.activityAt()), Json.write(conversation.getMetadata()),
                        millis(conversation.getCreatedAt()), millis(conversation.getUpdatedAt()));
                for (Participant p : initial) {
                    insertParticipant(conn, p);
                }
                return conversation;
            });
        } catch (UniqueViolationException e) {
            // Lost the race to a concurrent creator of the same DIRECT pair, or the same id was
            // created twice (a caller retry): return what is stored.
            Optional<Conversation> stored = conversation.getType() == Conversation.Type.DIRECT
                    ? findDirectConversation(conversation.getTenantId(), conversation.getDirectKey())
                    : Optional.empty();
            return stored.or(() -> findConversation(conversation.getTenantId(), conversation.getId()))
                    .orElseThrow(() -> conflict("Conversation", e));
        }
    }

    @Override
    public Optional<Conversation> findConversation(String tenantId, String conversationId) {
        return read(conn -> one(conn, "SELECT " + CONV_COLS + " FROM " + conversations + " WHERE tenant_id = ? AND id = ?",
                rs -> mapConversation(rs, ""), tenantId, conversationId));
    }

    @Override
    public Optional<Conversation> findDirectConversation(String tenantId, String directKey) {
        return read(conn -> one(conn, "SELECT " + CONV_COLS + " FROM " + conversations
                + " WHERE tenant_id = ? AND direct_key = ?", rs -> mapConversation(rs, ""), tenantId, directKey));
    }

    @Override
    public void updateConversation(Conversation c) {
        read(conn -> update(conn, "UPDATE " + conversations + " SET title = ?, description = ?, avatar_url = ?, metadata = ?,"
                        + " updated_at = ? WHERE tenant_id = ? AND id = ?",
                c.getTitle(), c.getDescription(), c.getAvatarUrl(), Json.write(c.getMetadata()),
                millis(c.getUpdatedAt() != null ? c.getUpdatedAt() : Instant.now()), c.getTenantId(), c.getId()));
    }

    @Override
    public List<InboxItem> listInbox(String tenantId, String userId, int limit, Instant before) {
        String select = "SELECT " + aliased("c", CONV_COLS) + ", " + aliased("p", PART_COLS)
                + " FROM " + participants + " p JOIN " + conversations + " c"
                + " ON c.tenant_id = p.tenant_id AND c.id = p.conversation_id"
                + " WHERE p.tenant_id = ? AND p.user_id = ? AND p.left_at IS NULL";
        RowMapper<InboxItem> mapper = rs -> new InboxItem(mapConversation(rs, "c_"), mapParticipant(rs, "p_"));
        return read(conn -> before == null
                ? list(conn, select + " ORDER BY c.activity_at DESC, c.id ASC LIMIT ?", mapper, tenantId, userId, limit)
                : list(conn, select + " AND c.activity_at < ? ORDER BY c.activity_at DESC, c.id ASC LIMIT ?", mapper,
                tenantId, userId, before.toEpochMilli(), limit));
    }

    private static String aliased(String alias, String columns) {
        StringBuilder sb = new StringBuilder();
        for (String col : columns.split(",")) {
            String c = col.trim();
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(alias).append('.').append(c).append(" AS ").append(alias).append('_').append(c);
        }
        return sb.toString();
    }

    private Conversation mapConversation(ResultSet rs, String p) throws SQLException {
        return Conversation.builder()
                .tenantId(rs.getString(p + "tenant_id"))
                .id(rs.getString(p + "id"))
                .type(Conversation.Type.valueOf(rs.getString(p + "type")))
                .directKey(rs.getString(p + "direct_key"))
                .title(rs.getString(p + "title"))
                .description(rs.getString(p + "description"))
                .avatarUrl(rs.getString(p + "avatar_url"))
                .createdBy(rs.getString(p + "created_by"))
                .lastSeq(rs.getLong(p + "last_seq"))
                .lastMessageId(rs.getString(p + "last_message_id"))
                .lastMessagePreview(rs.getString(p + "last_message_preview"))
                .lastMessageSenderId(rs.getString(p + "last_message_sender_id"))
                .lastMessageAt(instant(rs, p + "last_message_at"))
                .metadata(Json.readMap(rs.getString(p + "metadata")))
                .createdAt(instant(rs, p + "created_at"))
                .updatedAt(instant(rs, p + "updated_at"))
                .build();
    }

    // ================================================================== participants

    private void insertParticipant(Connection conn, Participant p) throws SQLException {
        update(conn, "INSERT INTO " + participants + " (" + PART_COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                p.getTenantId(), p.getConversationId(), p.getUserId(), p.getRole().name(), p.getJoinedSeq(),
                p.getDeliveredSeq(), p.getReadSeq(), p.isMuted(), p.isArchived(), p.isPinned(),
                millis(p.getJoinedAt()), millis(p.getLeftAt()), millis(p.getUpdatedAt()));
    }

    @Override
    public List<Participant> listParticipants(String tenantId, String conversationId) {
        return read(conn -> list(conn, "SELECT " + PART_COLS + " FROM " + participants
                        + " WHERE tenant_id = ? AND conversation_id = ? AND left_at IS NULL ORDER BY user_id",
                rs -> mapParticipant(rs, ""), tenantId, conversationId));
    }

    @Override
    public Optional<Participant> findParticipant(String tenantId, String conversationId, String userId) {
        return read(conn -> one(conn, "SELECT " + PART_COLS + " FROM " + participants
                        + " WHERE tenant_id = ? AND conversation_id = ? AND user_id = ?",
                rs -> mapParticipant(rs, ""), tenantId, conversationId, userId));
    }

    @Override
    public void upsertParticipant(Participant p) {
        try {
            transaction(conn -> writeParticipant(conn, p));
        } catch (UniqueViolationException raced) {
            // A concurrent first upsert inserted the row between our UPDATE and INSERT: update it now.
            retryAfterRace(() -> transaction(conn -> writeParticipant(conn, p)), "Participant");
        }
    }

    private Void writeParticipant(Connection conn, Participant p) throws SQLException {
        int updated = update(conn, "UPDATE " + participants + " SET role = ?, joined_seq = ?,"
                        + " delivered_seq = CASE WHEN delivered_seq < ? THEN ? ELSE delivered_seq END,"
                        + " read_seq = CASE WHEN read_seq < ? THEN ? ELSE read_seq END,"
                        + " muted = ?, archived = ?, pinned = ?, joined_at = ?, left_at = ?, updated_at = ?"
                        + " WHERE tenant_id = ? AND conversation_id = ? AND user_id = ?",
                p.getRole().name(), p.getJoinedSeq(), p.getDeliveredSeq(), p.getDeliveredSeq(),
                p.getReadSeq(), p.getReadSeq(), p.isMuted(),
                p.isArchived(), p.isPinned(), millis(p.getJoinedAt()), millis(p.getLeftAt()),
                millis(p.getUpdatedAt() != null ? p.getUpdatedAt() : Instant.now()),
                p.getTenantId(), p.getConversationId(), p.getUserId());
        if (updated == 0) {
            insertParticipant(conn, p);
        }
        return null;
    }

    @Override
    public void removeParticipant(String tenantId, String conversationId, String userId, Instant at) {
        read(conn -> update(conn, "UPDATE " + participants + " SET left_at = ?, updated_at = ?"
                        + " WHERE tenant_id = ? AND conversation_id = ? AND user_id = ?",
                millis(at), millis(at), tenantId, conversationId, userId));
    }

    @Override
    public void advanceReadState(String tenantId, String conversationId, String userId, Long deliveredSeq, Long readSeq) {
        if (deliveredSeq == null && readSeq == null) {
            return;
        }
        long delivered = Math.max(deliveredSeq == null ? 0 : deliveredSeq, readSeq == null ? 0 : readSeq);
        long read = readSeq == null ? 0 : readSeq;
        read(conn -> advance(conn, tenantId, conversationId, userId, delivered, read));
    }

    private int advance(Connection conn, String tenantId, String conversationId, String userId, long delivered, long read)
            throws SQLException {
        return update(conn, "UPDATE " + participants
                        + " SET delivered_seq = CASE WHEN delivered_seq < ? THEN ? ELSE delivered_seq END,"
                        + " read_seq = CASE WHEN read_seq < ? THEN ? ELSE read_seq END, updated_at = ?"
                        + " WHERE tenant_id = ? AND conversation_id = ? AND user_id = ?",
                delivered, delivered, read, read, System.currentTimeMillis(), tenantId, conversationId, userId);
    }

    private Participant mapParticipant(ResultSet rs, String p) throws SQLException {
        return Participant.builder()
                .tenantId(rs.getString(p + "tenant_id"))
                .conversationId(rs.getString(p + "conversation_id"))
                .userId(rs.getString(p + "user_id"))
                .role(Participant.Role.valueOf(rs.getString(p + "role")))
                .joinedSeq(rs.getLong(p + "joined_seq"))
                .deliveredSeq(rs.getLong(p + "delivered_seq"))
                .readSeq(rs.getLong(p + "read_seq"))
                .muted(rs.getBoolean(p + "muted"))
                .archived(rs.getBoolean(p + "archived"))
                .pinned(rs.getBoolean(p + "pinned"))
                .joinedAt(instant(rs, p + "joined_at"))
                .leftAt(instant(rs, p + "left_at"))
                .updatedAt(instant(rs, p + "updated_at"))
                .build();
    }

    // ================================================================== messages

    private static final String MSG_COLS = "tenant_id, conversation_id, seq, id, sender_id, client_msg_id, type, content,"
            + " attachment, reply_to_id, forwarded, reactions, edited, edited_at, deleted, deleted_at, metadata, created_at";

    @Override
    public AppendResult appendMessage(ChatMessage message) {
        try {
            // Duplicates are detected by the unique (conversation, sender, client_msg_id) index on insert:
            // the transaction (including the seq bump) rolls back and the original is returned below.
            return transaction(conn -> {
                long at = message.getCreatedAt().toEpochMilli();
                long seq = allocateSeq(conn, message, at);
                ChatMessage stored = message.toBuilder().seq(seq).build();
                update(conn, "INSERT INTO " + messages + " (" + MSG_COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        stored.getTenantId(), stored.getConversationId(), seq, stored.getId(), stored.getSenderId(),
                        stored.getClientMsgId(), stored.getType().name(), stored.getContent(),
                        Json.write(stored.getAttachment()), stored.getReplyToId(), stored.isForwarded(),
                        Json.write(stored.getReactions()), stored.isEdited(), millis(stored.getEditedAt()),
                        stored.isDeleted(), millis(stored.getDeletedAt()), Json.write(stored.getMetadata()), at);
                advance(conn, stored.getTenantId(), stored.getConversationId(), stored.getSenderId(), seq, seq);
                return new AppendResult(stored, false);
            });
        } catch (UniqueViolationException e) {
            // A concurrent retry with the same clientMsgId won the race: return the winner.
            Optional<ChatMessage> winner = message.getClientMsgId() == null ? Optional.empty()
                    : read(conn -> findByClientMsgId(conn, message));
            return new AppendResult(winner.orElseThrow(() -> conflict("Message", e)), true);
        }
    }

    /** Bump last_seq under the row lock and return it (one round trip on PostgreSQL via RETURNING). */
    private long allocateSeq(Connection conn, ChatMessage message, long at) throws SQLException {
        String update = "UPDATE " + conversations + " SET last_seq = last_seq + 1, last_message_id = ?,"
                + " last_message_preview = ?, last_message_sender_id = ?, last_message_at = ?, activity_at = ?,"
                + " updated_at = ? WHERE tenant_id = ? AND id = ?";
        Object[] params = {message.getId(), MessagePreview.of(message), message.getSenderId(), at, at, at,
                message.getTenantId(), message.getConversationId()};
        if (dialect == SqlDialect.POSTGRESQL) {
            try (PreparedStatement ps = conn.prepareStatement(update + " RETURNING last_seq")) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw DomainException.notFound("Conversation");
                    }
                    return rs.getLong(1);
                }
            }
        }
        if (update(conn, update, params) == 0) {
            throw DomainException.notFound("Conversation");
        }
        return count(conn, "SELECT last_seq FROM " + conversations + " WHERE tenant_id = ? AND id = ?",
                message.getTenantId(), message.getConversationId());
    }

    private Optional<ChatMessage> findByClientMsgId(Connection conn, ChatMessage m) throws SQLException {
        return one(conn, "SELECT " + MSG_COLS + " FROM " + messages
                        + " WHERE tenant_id = ? AND conversation_id = ? AND sender_id = ? AND client_msg_id = ?",
                this::mapMessage, m.getTenantId(), m.getConversationId(), m.getSenderId(), m.getClientMsgId());
    }

    @Override
    public Optional<ChatMessage> findMessage(String tenantId, String conversationId, String messageId) {
        return read(conn -> one(conn, "SELECT " + MSG_COLS + " FROM " + messages
                + " WHERE tenant_id = ? AND conversation_id = ? AND id = ?", this::mapMessage, tenantId, conversationId, messageId));
    }

    @Override
    public List<ChatMessage> listMessages(String tenantId, String conversationId, Long afterSeq, Long beforeSeq, int limit) {
        String base = "SELECT " + MSG_COLS + " FROM " + messages + " WHERE tenant_id = ? AND conversation_id = ?";
        if (afterSeq != null) {
            return read(conn -> list(conn, base + " AND seq > ? ORDER BY seq ASC LIMIT ?", this::mapMessage,
                    tenantId, conversationId, afterSeq, limit));
        }
        List<ChatMessage> newestFirst = read(conn -> beforeSeq == null
                ? list(conn, base + " ORDER BY seq DESC LIMIT ?", this::mapMessage, tenantId, conversationId, limit)
                : list(conn, base + " AND seq < ? ORDER BY seq DESC LIMIT ?", this::mapMessage,
                tenantId, conversationId, beforeSeq, limit));
        List<ChatMessage> ascending = new ArrayList<>(newestFirst);
        Collections.reverse(ascending);
        return ascending;
    }

    @Override
    public void updateMessage(ChatMessage m) {
        read(conn -> update(conn, "UPDATE " + messages + " SET content = ?, attachment = ?, reactions = ?, edited = ?,"
                        + " edited_at = ?, deleted = ?, deleted_at = ?, metadata = ?"
                        + " WHERE tenant_id = ? AND conversation_id = ? AND id = ?",
                m.getContent(), Json.write(m.getAttachment()), Json.write(m.getReactions()), m.isEdited(),
                millis(m.getEditedAt()), m.isDeleted(), millis(m.getDeletedAt()), Json.write(m.getMetadata()),
                m.getTenantId(), m.getConversationId(), m.getId()));
    }

    private ChatMessage mapMessage(ResultSet rs) throws SQLException {
        Map<String, Set<String>> reactions = Json.read(rs.getString("reactions"), REACTIONS);
        return ChatMessage.builder()
                .tenantId(rs.getString("tenant_id"))
                .conversationId(rs.getString("conversation_id"))
                .seq(rs.getLong("seq"))
                .id(rs.getString("id"))
                .senderId(rs.getString("sender_id"))
                .clientMsgId(rs.getString("client_msg_id"))
                .type(ChatMessage.Type.valueOf(rs.getString("type")))
                .content(rs.getString("content"))
                .attachment(Json.read(rs.getString("attachment"), ChatMessage.Attachment.class))
                .replyToId(rs.getString("reply_to_id"))
                .forwarded(rs.getBoolean("forwarded"))
                .reactions(reactions == null ? new LinkedHashMap<>() : new LinkedHashMap<>(reactions))
                .edited(rs.getBoolean("edited"))
                .editedAt(instant(rs, "edited_at"))
                .deleted(rs.getBoolean("deleted"))
                .deletedAt(instant(rs, "deleted_at"))
                .metadata(Json.readMap(rs.getString("metadata")))
                .createdAt(instant(rs, "created_at"))
                .build();
    }

    // ================================================================== stats

    @Override
    public StoreStats stats(String tenantId) {
        return read(conn -> {
            String where = tenantId == null ? "" : " WHERE tenant_id = ?";
            Object[] params = tenantId == null ? new Object[0] : new Object[]{tenantId};
            return new StoreStats(
                    count(conn, "SELECT COUNT(*) FROM " + users + where, params),
                    count(conn, "SELECT COUNT(*) FROM " + conversations + where, params),
                    count(conn, "SELECT COUNT(*) FROM " + messages + where, params));
        });
    }
}
