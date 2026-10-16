package com.ayush.chat.store.cassandra;

import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.exception.DomainException;
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
import com.ayush.chat.store.model.InboxItem;
import com.ayush.chat.store.model.Participant;
import com.ayush.chat.store.model.StoreStats;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.DriverException;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.BatchType;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.servererrors.DefaultWriteType;
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException;
import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * {@link MessageStore} on Apache Cassandra and CQL-compatible databases, with a query-first
 * model (one table per access path, every partition key starting with {@code tenant_id}):
 * <pre>
 *   users                ((tenant_id), id)
 *   blocks               ((tenant_id, blocker_id), blocked_id)
 *   conversations        ((tenant_id, id))                         last_seq allocated by LWT compare-and-set
 *   direct_conversations ((tenant_id, direct_key))                 INSERT IF NOT EXISTS claim
 *   participants         ((tenant_id, conversation_id), user_id)
 *   user_conversations   ((tenant_id, user_id), conversation_id)   inbox membership index
 *   messages             ((tenant_id, conversation_id, bucket), seq)   bucket = seq / 1000
 *   messages_by_id       ((tenant_id, conversation_id, id))         -> seq
 *   client_msg_dedupe    ((tenant_id, conversation_id, sender_id, client_msg_id))   INSERT IF NOT EXISTS claim
 *   counters             ((scope, shard), name)                    sharded counters for stats
 * </pre>
 * Read/delivered watermarks are monotonic without LWT: they are written {@code USING TIMESTAMP
 * <seq>}, so Cassandra's last-write-wins keeps the highest value regardless of arrival order.
 * Those two cells are never written with a wall-clock timestamp.
 * <p>
 * Rows created implicitly by an {@code UPDATE} on a missing key (e.g. a receipt for a user who
 * is not a participant) are "ghosts" without their identity columns and are ignored on read.
 * <p>
 * Driver failures in data operations, and calls after {@link #close()}, surface as
 * {@link InfrastructureUnavailableException} (503).
 */
@Slf4j
public class CassandraMessageStore implements MessageStore {

    private static final String COMPONENT = ConnectorKind.MESSAGE_STORE.getDisplayName();

    /** Messages per partition. Bounds partition size; history paging walks buckets. */
    static final long BUCKET_SIZE = 1_000;
    private static final int COUNTER_SHARDS = 8;
    private static final List<Integer> SHARDS = IntStream.range(0, COUNTER_SHARDS).boxed().toList();
    private static final String GLOBAL_SCOPE = "global";
    /** Upper bound on memberships read per inbox request (the inbox is sorted client-side). */
    private static final int INBOX_SCAN_LIMIT = 5_000;
    private static final int MAX_SEQ_ATTEMPTS = 200;
    private static final long CLAIM_STALE_MILLIS = 10_000;
    private static final int PARALLELISM = 64;
    private static final int IN_BATCH = 100;
    private static final Duration DDL_TIMEOUT = Duration.ofSeconds(60);
    private static final Pattern KEYSPACE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,47}");
    private static final Pattern TABLE_PREFIX = Pattern.compile("[A-Za-z0-9_]{0,24}");
    private static final TypeReference<List<Reaction>> REACTIONS = new TypeReference<>() {
    };

    /** Connection and schema options. {@code username} may be null for unauthenticated clusters. */
    public record Options(List<InetSocketAddress> contactPoints, String localDatacenter, String keyspace,
                          String username, String password, boolean createKeyspace, int replicationFactor,
                          String tablePrefix) {
    }

    /** One emoji's reactions, stored as an ordered JSON array so insertion order round-trips. */
    record Reaction(String emoji, List<String> userIds) {
    }

    private final CqlSession session;
    private final String keyspace;
    private final String localDatacenter;
    private final String prefix;
    private final AtomicBoolean closed = new AtomicBoolean();

    private PreparedStatement selectUser;
    private PreparedStatement selectUsersIn;
    private PreparedStatement selectTenantUsers;
    private PreparedStatement selectUserCreatedAt;
    private PreparedStatement claimUserCreatedAt;
    private PreparedStatement updateUser;
    private PreparedStatement updateUserWithLastSeen;
    private PreparedStatement updateLastSeen;
    private PreparedStatement insertBlock;
    private PreparedStatement deleteBlock;
    private PreparedStatement selectBlock;
    private PreparedStatement selectBlocks;
    private PreparedStatement insertConversation;
    private PreparedStatement selectConversation;
    private PreparedStatement selectLastSeq;
    private PreparedStatement casLastSeq;
    private PreparedStatement updateConversation;
    private PreparedStatement claimDirect;
    private PreparedStatement takeoverDirect;
    private PreparedStatement selectDirect;
    private PreparedStatement insertParticipant;
    private PreparedStatement advanceDelivered;
    private PreparedStatement advanceRead;
    private PreparedStatement touchParticipant;
    private PreparedStatement leaveParticipant;
    private PreparedStatement selectParticipant;
    private PreparedStatement selectParticipants;
    private PreparedStatement insertMembership;
    private PreparedStatement deleteMembership;
    private PreparedStatement selectMemberships;
    private PreparedStatement insertMessage;
    private PreparedStatement insertMessageId;
    private PreparedStatement selectMessageSeq;
    private PreparedStatement selectMessage;
    private PreparedStatement selectMessagesAfter;
    private PreparedStatement selectMessagesBefore;
    private PreparedStatement updateMessage;
    private PreparedStatement claimClientMsg;
    private PreparedStatement takeoverClientMsg;
    private PreparedStatement releaseClientMsg;
    private PreparedStatement incrementCounter;
    private PreparedStatement selectCounters;

    public CassandraMessageStore(Options options) {
        if (options.contactPoints() == null || options.contactPoints().isEmpty()) {
            throw new IllegalArgumentException("At least one Cassandra contact point is required");
        }
        if (!KEYSPACE_NAME.matcher(options.keyspace()).matches()) {
            throw new IllegalArgumentException("Invalid keyspace name '" + options.keyspace()
                    + "': use letters, digits and underscores, starting with a letter");
        }
        String tablePrefix = options.tablePrefix() == null ? "" : options.tablePrefix();
        if (!TABLE_PREFIX.matcher(tablePrefix).matches()) {
            throw new IllegalArgumentException("Invalid table prefix '" + tablePrefix
                    + "': use up to 24 letters, digits and underscores");
        }
        this.keyspace = options.keyspace().toLowerCase(Locale.ROOT);
        this.localDatacenter = options.localDatacenter();
        this.prefix = tablePrefix.toLowerCase(Locale.ROOT);

        DriverConfigLoader config = DriverConfigLoader.programmaticBuilder()
                .withString(DefaultDriverOption.SESSION_NAME, "chat-store")
                .withString(DefaultDriverOption.REQUEST_CONSISTENCY, DefaultConsistencyLevel.LOCAL_QUORUM.name())
                .withString(DefaultDriverOption.REQUEST_SERIAL_CONSISTENCY, DefaultConsistencyLevel.SERIAL.name())
                .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(10))
                .withDuration(DefaultDriverOption.CONNECTION_INIT_QUERY_TIMEOUT, Duration.ofSeconds(10))
                .withDuration(DefaultDriverOption.CONTROL_CONNECTION_AGREEMENT_TIMEOUT, Duration.ofSeconds(30))
                .build();
        CqlSessionBuilder builder = CqlSession.builder()
                .withConfigLoader(config)
                .addContactPoints(options.contactPoints())
                .withLocalDatacenter(options.localDatacenter());
        if (options.username() != null && !options.username().isBlank()) {
            builder.withAuthCredentials(options.username(), options.password() == null ? "" : options.password());
        }
        try {
            this.session = builder.build();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not connect to Cassandra at " + options.contactPoints()
                    + " (datacenter " + options.localDatacenter() + "): " + e.getMessage(), e);
        }
        try {
            createSchema(options);
            prepareStatements();
        } catch (RuntimeException e) {
            session.close();
            throw new IllegalStateException("Could not initialise Cassandra keyspace '" + keyspace + "': "
                    + e.getMessage(), e);
        }
    }

    private String table(String name) {
        return keyspace + "." + prefix + name;
    }

    private void createSchema(Options options) {
        if (options.createKeyspace()) {
            ddl("CREATE KEYSPACE IF NOT EXISTS " + keyspace + " WITH replication = {'class': 'NetworkTopologyStrategy', '"
                    + options.localDatacenter().replace("'", "''") + "': " + Math.max(1, options.replicationFactor()) + "}");
        }
        ddl("CREATE TABLE IF NOT EXISTS " + table("users") + " (tenant_id text, id text, display_name text, email text, "
                + "phone text, avatar_url text, about text, push_tokens list<text>, metadata text, deactivated boolean, "
                + "created_at timestamp, updated_at timestamp, last_seen_at timestamp, PRIMARY KEY ((tenant_id), id))");
        ddl("CREATE TABLE IF NOT EXISTS " + table("blocks") + " (tenant_id text, blocker_id text, blocked_id text, "
                + "created_at timestamp, PRIMARY KEY ((tenant_id, blocker_id), blocked_id))");
        ddl("CREATE TABLE IF NOT EXISTS " + table("conversations") + " (tenant_id text, id text, type text, "
                + "direct_key text, title text, description text, avatar_url text, created_by text, last_seq bigint, "
                + "last_message_id text, last_message_preview text, last_message_sender_id text, "
                + "last_message_at timestamp, metadata text, created_at timestamp, updated_at timestamp, "
                + "PRIMARY KEY ((tenant_id, id)))");
        ddl("CREATE TABLE IF NOT EXISTS " + table("direct_conversations") + " (tenant_id text, direct_key text, "
                + "conversation_id text, claimed_at timestamp, PRIMARY KEY ((tenant_id, direct_key)))");
        ddl("CREATE TABLE IF NOT EXISTS " + table("participants") + " (tenant_id text, conversation_id text, "
                + "user_id text, role text, joined_seq bigint, delivered_seq bigint, read_seq bigint, muted boolean, "
                + "archived boolean, pinned boolean, joined_at timestamp, left_at timestamp, updated_at timestamp, "
                + "PRIMARY KEY ((tenant_id, conversation_id), user_id))");
        ddl("CREATE TABLE IF NOT EXISTS " + table("user_conversations") + " (tenant_id text, user_id text, "
                + "conversation_id text, joined_at timestamp, PRIMARY KEY ((tenant_id, user_id), conversation_id))");
        ddl("CREATE TABLE IF NOT EXISTS " + table("messages") + " (tenant_id text, conversation_id text, bucket bigint, "
                + "seq bigint, id text, sender_id text, client_msg_id text, type text, content text, attachment text, "
                + "reply_to_id text, forwarded boolean, reactions text, edited boolean, edited_at timestamp, "
                + "deleted boolean, deleted_at timestamp, metadata text, created_at timestamp, "
                + "PRIMARY KEY ((tenant_id, conversation_id, bucket), seq)) WITH CLUSTERING ORDER BY (seq ASC)");
        ddl("CREATE TABLE IF NOT EXISTS " + table("messages_by_id") + " (tenant_id text, conversation_id text, id text, "
                + "seq bigint, PRIMARY KEY ((tenant_id, conversation_id, id)))");
        ddl("CREATE TABLE IF NOT EXISTS " + table("client_msg_dedupe") + " (tenant_id text, conversation_id text, "
                + "sender_id text, client_msg_id text, message_id text, claimed_at timestamp, "
                + "PRIMARY KEY ((tenant_id, conversation_id, sender_id, client_msg_id)))");
        ddl("CREATE TABLE IF NOT EXISTS " + table("counters") + " (scope text, shard int, name text, total counter, "
                + "PRIMARY KEY ((scope, shard), name))");
    }

    private void ddl(String cql) {
        session.execute(SimpleStatement.newInstance(cql).setTimeout(DDL_TIMEOUT));
    }

    private void prepareStatements() {
        String users = table("users");
        selectUser = prepare("SELECT * FROM " + users + " WHERE tenant_id = ? AND id = ?", true);
        selectUsersIn = prepare("SELECT * FROM " + users + " WHERE tenant_id = ? AND id IN ?", true);
        selectTenantUsers = prepare("SELECT * FROM " + users + " WHERE tenant_id = ?", true);
        selectUserCreatedAt = prepare("SELECT created_at FROM " + users + " WHERE tenant_id = ? AND id = ?", true);
        claimUserCreatedAt = prepare("UPDATE " + users + " SET created_at = ? WHERE tenant_id = ? AND id = ? "
                + "IF created_at = null", false);
        String userFields = "display_name = ?, email = ?, phone = ?, avatar_url = ?, about = ?, push_tokens = ?, "
                + "metadata = ?, deactivated = ?, updated_at = ?";
        updateUser = prepare("UPDATE " + users + " SET " + userFields + " WHERE tenant_id = ? AND id = ?", true);
        updateUserWithLastSeen = prepare("UPDATE " + users + " SET " + userFields + ", last_seen_at = ? "
                + "WHERE tenant_id = ? AND id = ?", true);
        updateLastSeen = prepare("UPDATE " + users + " SET last_seen_at = ? WHERE tenant_id = ? AND id = ?", true);

        String blocks = table("blocks");
        insertBlock = prepare("INSERT INTO " + blocks + " (tenant_id, blocker_id, blocked_id, created_at) "
                + "VALUES (?, ?, ?, ?)", true);
        deleteBlock = prepare("DELETE FROM " + blocks + " WHERE tenant_id = ? AND blocker_id = ? AND blocked_id = ?", true);
        selectBlock = prepare("SELECT blocked_id FROM " + blocks + " WHERE tenant_id = ? AND blocker_id = ? "
                + "AND blocked_id = ?", true);
        selectBlocks = prepare("SELECT blocked_id FROM " + blocks + " WHERE tenant_id = ? AND blocker_id = ?", true);

        String conversations = table("conversations");
        insertConversation = prepare("INSERT INTO " + conversations + " (tenant_id, id, type, direct_key, title, "
                + "description, avatar_url, created_by, last_seq, last_message_id, last_message_preview, "
                + "last_message_sender_id, last_message_at, metadata, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) IF NOT EXISTS", false);
        selectConversation = prepare("SELECT * FROM " + conversations + " WHERE tenant_id = ? AND id = ?", true);
        selectLastSeq = prepare("SELECT last_seq, last_message_id FROM " + conversations
                + " WHERE tenant_id = ? AND id = ?", true);
        casLastSeq = prepare("UPDATE " + conversations + " SET last_seq = ?, last_message_id = ?, "
                + "last_message_preview = ?, last_message_sender_id = ?, last_message_at = ?, updated_at = ? "
                + "WHERE tenant_id = ? AND id = ? IF last_seq = ?", false);
        updateConversation = prepare("UPDATE " + conversations + " SET title = ?, description = ?, avatar_url = ?, "
                + "metadata = ?, updated_at = ? WHERE tenant_id = ? AND id = ? IF EXISTS", false);

        String direct = table("direct_conversations");
        claimDirect = prepare("INSERT INTO " + direct + " (tenant_id, direct_key, conversation_id, claimed_at) "
                + "VALUES (?, ?, ?, ?) IF NOT EXISTS", false);
        takeoverDirect = prepare("UPDATE " + direct + " SET conversation_id = ?, claimed_at = ? "
                + "WHERE tenant_id = ? AND direct_key = ? IF conversation_id = ?", false);
        selectDirect = prepare("SELECT conversation_id FROM " + direct + " WHERE tenant_id = ? AND direct_key = ?", true);

        String participants = table("participants");
        String participantKey = " WHERE tenant_id = ? AND conversation_id = ? AND user_id = ?";
        insertParticipant = prepare("INSERT INTO " + participants + " (tenant_id, conversation_id, user_id, role, "
                + "joined_seq, muted, archived, pinned, joined_at, left_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", true);
        advanceDelivered = prepare("UPDATE " + participants + " USING TIMESTAMP ? SET delivered_seq = ?"
                + participantKey, true);
        advanceRead = prepare("UPDATE " + participants + " USING TIMESTAMP ? SET read_seq = ?" + participantKey, true);
        touchParticipant = prepare("UPDATE " + participants + " SET updated_at = ?" + participantKey, true);
        leaveParticipant = prepare("UPDATE " + participants + " SET left_at = ?, updated_at = ?" + participantKey, true);
        selectParticipant = prepare("SELECT * FROM " + participants + participantKey, true);
        selectParticipants = prepare("SELECT * FROM " + participants + " WHERE tenant_id = ? AND conversation_id = ?", true);

        String memberships = table("user_conversations");
        insertMembership = prepare("INSERT INTO " + memberships + " (tenant_id, user_id, conversation_id, joined_at) "
                + "VALUES (?, ?, ?, ?)", true);
        deleteMembership = prepare("DELETE FROM " + memberships + " WHERE tenant_id = ? AND user_id = ? "
                + "AND conversation_id = ?", true);
        selectMemberships = prepare("SELECT conversation_id FROM " + memberships + " WHERE tenant_id = ? AND user_id = ? "
                + "LIMIT ?", true);

        String messages = table("messages");
        String messageKey = " WHERE tenant_id = ? AND conversation_id = ? AND bucket = ?";
        insertMessage = prepare("INSERT INTO " + messages + " (tenant_id, conversation_id, bucket, seq, id, sender_id, "
                + "client_msg_id, type, content, attachment, reply_to_id, forwarded, reactions, edited, edited_at, "
                + "deleted, deleted_at, metadata, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", true);
        insertMessageId = prepare("INSERT INTO " + table("messages_by_id") + " (tenant_id, conversation_id, id, seq) "
                + "VALUES (?, ?, ?, ?)", true);
        selectMessageSeq = prepare("SELECT seq FROM " + table("messages_by_id")
                + " WHERE tenant_id = ? AND conversation_id = ? AND id = ?", true);
        selectMessage = prepare("SELECT * FROM " + messages + messageKey + " AND seq = ?", true);
        selectMessagesAfter = prepare("SELECT * FROM " + messages + messageKey + " AND seq > ? LIMIT ?", true);
        selectMessagesBefore = prepare("SELECT * FROM " + messages + messageKey + " AND seq < ? "
                + "ORDER BY seq DESC LIMIT ?", true);
        updateMessage = prepare("UPDATE " + messages + " SET content = ?, attachment = ?, reactions = ?, edited = ?, "
                + "edited_at = ?, deleted = ?, deleted_at = ?, metadata = ?" + messageKey + " AND seq = ?", true);

        String dedupe = table("client_msg_dedupe");
        String dedupeKey = " WHERE tenant_id = ? AND conversation_id = ? AND sender_id = ? AND client_msg_id = ?";
        claimClientMsg = prepare("INSERT INTO " + dedupe + " (tenant_id, conversation_id, sender_id, client_msg_id, "
                + "message_id, claimed_at) VALUES (?, ?, ?, ?, ?, ?) IF NOT EXISTS", false);
        takeoverClientMsg = prepare("UPDATE " + dedupe + " SET message_id = ?, claimed_at = ?" + dedupeKey
                + " IF message_id = ?", false);
        releaseClientMsg = prepare("DELETE FROM " + dedupe + dedupeKey + " IF message_id = ?", false);

        String counters = table("counters");
        incrementCounter = prepare("UPDATE " + counters + " SET total = total + ? WHERE scope = ? AND shard = ? "
                + "AND name = ?", false);
        selectCounters = prepare("SELECT name, total FROM " + counters + " WHERE scope = ? AND shard IN ?", true);
    }

    /**
     * Prepares a statement, waiting briefly for tables that some managed services (e.g. Amazon
     * Keyspaces) create asynchronously after {@code CREATE TABLE} returns.
     */
    private PreparedStatement prepare(String cql, boolean idempotent) {
        SimpleStatement statement = SimpleStatement.newInstance(cql).setIdempotent(idempotent);
        long deadline = System.currentTimeMillis() + DDL_TIMEOUT.toMillis();
        while (true) {
            try {
                return session.prepare(statement);
            } catch (InvalidQueryException e) {
                String message = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
                boolean pending = message.contains("unconfigured table") || message.contains("does not exist");
                if (!pending || System.currentTimeMillis() > deadline) {
                    throw e;
                }
                StoreSupport.backoff(COMPONENT, 1_000);
            }
        }
    }

    /** Runs one data operation: driver failures (and use after close) become a retryable 503. */
    private <T> T call(Supplier<T> operation) {
        if (closed.get()) {
            throw new InfrastructureUnavailableException(COMPONENT, "store closed");
        }
        try {
            return operation.get();
        } catch (DriverException e) {
            throw new InfrastructureUnavailableException(COMPONENT, e);
        }
    }

    private void run(Runnable operation) {
        call(() -> {
            operation.run();
            return null;
        });
    }

    @Override
    public String providerId() {
        return "cassandra";
    }

    @Override
    public HealthStatus health() {
        try {
            Row row = session.execute(SimpleStatement.newInstance("SELECT release_version FROM system.local")).one();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("keyspace", keyspace);
            details.put("datacenter", localDatacenter);
            details.put("nodes", session.getMetadata().getNodes().size());
            if (row != null && !row.isNull("release_version")) {
                details.put("releaseVersion", row.getString("release_version"));
            }
            return HealthStatus.up("Cassandra reachable", details);
        } catch (RuntimeException e) {
            return HealthStatus.down("Cassandra unreachable: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                session.close();
            } catch (RuntimeException e) {
                log.warn("Error closing Cassandra session: {}", e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ users

    @Override
    public ChatUser upsertUser(ChatUser user) {
        return call(() -> {
            String tenantId = user.getTenantId();
            String userId = user.getId();
            // created_at is written once, by compare-and-set, so it survives later upserts and marks the
            // row as a real user (rows touched only by updateLastSeen have no created_at).
            Row existing = session.execute(selectUserCreatedAt.bind(tenantId, userId)).one();
            boolean created = false;
            if (existing == null || existing.isNull("created_at")) {
                Instant createdAt = user.getCreatedAt() != null ? user.getCreatedAt() : Instant.now();
                created = session.execute(claimUserCreatedAt.bind(createdAt, tenantId, userId)).wasApplied();
            }
            List<String> tokens = user.getPushTokens() == null ? List.of() : new ArrayList<>(user.getPushTokens());
            Object[] fields = {user.getDisplayName(), user.getEmail(), user.getPhone(), user.getAvatarUrl(), user.getAbout(),
                    tokens, Json.write(user.getMetadata()), user.isDeactivated(), user.getUpdatedAt()};
            if (user.getLastSeenAt() != null) {
                session.execute(updateUserWithLastSeen.bind(
                        StoreSupport.concat(fields, user.getLastSeenAt(), tenantId, userId)));
            } else {
                session.execute(updateUser.bind(StoreSupport.concat(fields, tenantId, userId)));
            }
            if (created) {
                count(tenantId, "users");
            }
            return findUser(tenantId, userId).orElseThrow();
        });
    }

    @Override
    public Optional<ChatUser> findUser(String tenantId, String userId) {
        return call(() -> Optional.ofNullable(toUser(session.execute(selectUser.bind(tenantId, userId)).one())));
    }

    @Override
    public List<ChatUser> findUsers(String tenantId, Collection<String> userIds) {
        return call(() -> {
            List<String> ids = userIds.stream().distinct().toList();
            Map<String, ChatUser> found = new LinkedHashMap<>();
            for (int from = 0; from < ids.size(); from += IN_BATCH) {
                List<String> chunk = ids.subList(from, Math.min(ids.size(), from + IN_BATCH));
                for (Row row : session.execute(selectUsersIn.bind(tenantId, chunk))) {
                    ChatUser u = toUser(row);
                    if (u != null) {
                        found.put(u.getId(), u);
                    }
                }
            }
            return ids.stream().map(found::get).filter(Objects::nonNull).toList();
        });
    }

    /** Scans the tenant's user partition (ordered by id) and filters client-side. */
    @Override
    public List<ChatUser> searchUsers(String tenantId, String query, int offset, int limit) {
        return call(() -> {
            if (limit <= 0) {
                return List.of();
            }
            String q = UserSearch.normalize(query);
            List<ChatUser> result = new ArrayList<>();
            int skipped = 0;
            for (Row row : session.execute(selectTenantUsers.bind(tenantId).setPageSize(500))) {
                ChatUser u = toUser(row);
                if (u == null || !UserSearch.matches(u, q)) {
                    continue;
                }
                if (skipped++ < offset) {
                    continue;
                }
                result.add(u);
                if (result.size() >= limit) {
                    break;
                }
            }
            return result;
        });
    }

    @Override
    public long countUsers(String tenantId) {
        return call(() -> counters(tenantScope(tenantId)).getOrDefault("users", 0L));
    }

    @Override
    public void updateLastSeen(String tenantId, String userId, Instant lastSeenAt) {
        run(() -> session.execute(updateLastSeen.bind(lastSeenAt, tenantId, userId)));
    }

    // ------------------------------------------------------------------ blocks

    @Override
    public void setBlocked(String tenantId, String blockerId, String blockedId, boolean blocked) {
        run(() -> {
            if (blocked) {
                session.execute(insertBlock.bind(tenantId, blockerId, blockedId, Instant.now()));
            } else {
                session.execute(deleteBlock.bind(tenantId, blockerId, blockedId));
            }
        });
    }

    @Override
    public boolean isBlocked(String tenantId, String blockerId, String blockedId) {
        return call(() -> session.execute(selectBlock.bind(tenantId, blockerId, blockedId)).one() != null);
    }

    @Override
    public List<String> listBlocked(String tenantId, String blockerId) {
        return call(() -> {
            List<String> result = new ArrayList<>();
            for (Row row : session.execute(selectBlocks.bind(tenantId, blockerId))) {
                result.add(row.getString("blocked_id"));
            }
            return result;
        });
    }

    // ------------------------------------------------------------------ conversations

    @Override
    public Conversation createConversation(Conversation conversation, List<Participant> initial) {
        return call(() -> {
            String tenantId = conversation.getTenantId();
            String id = conversation.getId();
            if (conversation.getType() == Conversation.Type.DIRECT) {
                String directKey = conversation.getDirectKey();
                Optional<Conversation> existing = claim(
                        () -> session.execute(claimDirect.bind(tenantId, directKey, id, Instant.now())),
                        stale -> session.execute(takeoverDirect.bind(id, Instant.now(), tenantId, directKey, stale))
                                .wasApplied(),
                        "conversation_id", id, owner -> findConversation(tenantId, owner));
                if (existing.isPresent()) {
                    return existing.get();
                }
            }
            // Members first: whoever can see the conversation row can also see its participants.
            parallel(initial, p -> writeParticipant(p, true));
            ResultSet inserted = session.execute(bindUnsetNulls(insertConversation, tenantId, id,
                    conversation.getType() == null ? null : conversation.getType().name(), conversation.getDirectKey(),
                    conversation.getTitle(), conversation.getDescription(), conversation.getAvatarUrl(),
                    conversation.getCreatedBy(), conversation.getLastSeq(), conversation.getLastMessageId(),
                    conversation.getLastMessagePreview(), conversation.getLastMessageSenderId(),
                    conversation.getLastMessageAt(), Json.write(conversation.getMetadata()), conversation.getCreatedAt(),
                    conversation.getUpdatedAt()));
            if (!inserted.wasApplied()) {
                return toConversation(inserted.one()); // same id created twice: keep what is stored
            }
            count(tenantId, "conversations");
            return findConversation(tenantId, id).orElseThrow();
        });
    }

    @Override
    public Optional<Conversation> findConversation(String tenantId, String conversationId) {
        return call(() -> Optional.ofNullable(toConversation(
                session.execute(selectConversation.bind(tenantId, conversationId)).one())));
    }

    @Override
    public Optional<Conversation> findDirectConversation(String tenantId, String directKey) {
        return call(() -> {
            Row row = session.execute(selectDirect.bind(tenantId, directKey)).one();
            return row == null ? Optional.empty() : findConversation(tenantId, row.getString("conversation_id"));
        });
    }

    @Override
    public void updateConversation(Conversation conversation) {
        run(() -> // LWT so every write to a conversation row goes through Paxos (consistent cell timestamps)
        // and a missing conversation is not resurrected as a partial row.
        session.execute(updateConversation.bind(conversation.getTitle(), conversation.getDescription(),
                conversation.getAvatarUrl(), Json.write(conversation.getMetadata()), conversation.getUpdatedAt(),
                conversation.getTenantId(), conversation.getId())));
    }

    @Override
    public List<InboxItem> listInbox(String tenantId, String userId, int limit, Instant before) {
        return call(() -> {
            if (limit <= 0) {
                return List.of();
            }
            List<String> conversationIds = new ArrayList<>();
            for (Row row : session.execute(selectMemberships.bind(tenantId, userId, INBOX_SCAN_LIMIT))) {
                conversationIds.add(row.getString("conversation_id"));
            }
            List<InboxItem> items = parallel(conversationIds, id -> {
                CompletionStage<AsyncResultSet> conversation = session.executeAsync(selectConversation.bind(tenantId, id));
                CompletionStage<AsyncResultSet> self = session.executeAsync(selectParticipant.bind(tenantId, id, userId));
                return conversation.thenCombine(self, (c, p) -> {
                    Conversation conv = toConversation(c.one());
                    Participant participant = toParticipant(p.one());
                    return conv == null || participant == null || !participant.isActive() ? null
                            : new InboxItem(conv, participant);
                });
            });
            return items.stream()
                    .filter(Objects::nonNull)
                    .filter(i -> before == null || i.conversation().activityAt().isBefore(before))
                    .sorted(Comparator.comparing((InboxItem i) -> i.conversation().activityAt()).reversed()
                            .thenComparing(i -> i.conversation().getId()))
                    .limit(limit)
                    .toList();
        });
    }

    // ------------------------------------------------------------------ participants

    @Override
    public List<Participant> listParticipants(String tenantId, String conversationId) {
        return call(() -> {
            List<Participant> result = new ArrayList<>();
            for (Row row : session.execute(selectParticipants.bind(tenantId, conversationId))) {
                Participant p = toParticipant(row);
                if (p != null && p.isActive()) {
                    result.add(p);
                }
            }
            return result;
        });
    }

    @Override
    public Optional<Participant> findParticipant(String tenantId, String conversationId, String userId) {
        return call(() -> Optional.ofNullable(toParticipant(
                session.execute(selectParticipant.bind(tenantId, conversationId, userId)).one())));
    }

    /**
     * Replaces the participant's attributes and inbox membership. Watermarks keep max semantics
     * (they are timestamped by value), so an upsert can raise but never lower them.
     */
    @Override
    public void upsertParticipant(Participant participant) {
        run(() -> awaitAll(List.of(writeParticipant(participant, false))));
    }

    private CompletionStage<Void> writeParticipant(Participant p, boolean fresh) {
        Object[] values = {p.getTenantId(), p.getConversationId(), p.getUserId(),
                p.getRole() == null ? Participant.Role.MEMBER.name() : p.getRole().name(), p.getJoinedSeq(),
                p.isMuted(), p.isArchived(), p.isPinned(), p.getJoinedAt(), p.getLeftAt(), p.getUpdatedAt()};
        // a fresh row has nothing to overwrite, so nulls stay unset instead of writing tombstones
        BoundStatement row = fresh ? bindUnsetNulls(insertParticipant, values) : insertParticipant.bind(values);
        BoundStatement membership = p.isActive()
                ? bindUnsetNulls(insertMembership, p.getTenantId(), p.getUserId(), p.getConversationId(), p.getJoinedAt())
                : deleteMembership.bind(p.getTenantId(), p.getUserId(), p.getConversationId());
        List<CompletionStage<?>> writes = new ArrayList<>();
        writes.add(session.executeAsync(BatchStatement.newInstance(BatchType.LOGGED, row, membership)));
        writes.add(session.executeAsync(advanceDelivered.bind(p.getDeliveredSeq(), p.getDeliveredSeq(),
                p.getTenantId(), p.getConversationId(), p.getUserId())));
        writes.add(session.executeAsync(advanceRead.bind(p.getReadSeq(), p.getReadSeq(),
                p.getTenantId(), p.getConversationId(), p.getUserId())));
        return CompletableFuture.allOf(writes.stream().map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new));
    }

    @Override
    public void removeParticipant(String tenantId, String conversationId, String userId, Instant at) {
        run(() -> session.execute(BatchStatement.newInstance(BatchType.LOGGED,
                leaveParticipant.bind(at, at, tenantId, conversationId, userId),
                deleteMembership.bind(tenantId, userId, conversationId))));
    }

    @Override
    public void advanceReadState(String tenantId, String conversationId, String userId, Long deliveredSeq, Long readSeq) {
        run(() -> {
            Long delivered = deliveredSeq == null ? readSeq
                    : readSeq == null ? deliveredSeq : Math.max(deliveredSeq, readSeq);
            if (delivered == null) {
                return;
            }
            List<CompletionStage<?>> writes = new ArrayList<>();
            writes.add(session.executeAsync(advanceDelivered.bind(delivered, delivered, tenantId, conversationId, userId)));
            if (readSeq != null) {
                writes.add(session.executeAsync(advanceRead.bind(readSeq, readSeq, tenantId, conversationId, userId)));
            }
            writes.add(session.executeAsync(touchParticipant.bind(Instant.now(), tenantId, conversationId, userId)));
            awaitAll(writes);
        });
    }

    // ------------------------------------------------------------------ messages

    @Override
    public AppendResult appendMessage(ChatMessage message) {
        return call(() -> {
            String tenantId = message.getTenantId();
            String conversationId = message.getConversationId();
            String senderId = message.getSenderId();
            String clientMsgId = message.getClientMsgId();
            String id = message.getId();
            if (clientMsgId != null) {
                Optional<ChatMessage> original = claim(
                        () -> session.execute(claimClientMsg.bind(tenantId, conversationId, senderId, clientMsgId, id,
                                Instant.now())),
                        stale -> session.execute(takeoverClientMsg.bind(id, Instant.now(), tenantId, conversationId,
                                senderId, clientMsgId, stale)).wasApplied(),
                        "message_id", id, owner -> findMessage(tenantId, conversationId, owner));
                if (original.isPresent()) {
                    return new AppendResult(original.get(), true);
                }
            }
            long seq = allocateSeq(message);
            if (seq < 0) {
                if (clientMsgId != null) {
                    session.execute(releaseClientMsg.bind(tenantId, conversationId, senderId, clientMsgId, id));
                }
                throw DomainException.notFound("Conversation");
            }
            session.execute(BatchStatement.newInstance(BatchType.LOGGED,
                    bindUnsetNulls(insertMessage, tenantId, conversationId, seq / BUCKET_SIZE, seq, id, senderId,
                            clientMsgId, message.getType() == null ? ChatMessage.Type.TEXT.name() : message.getType().name(),
                            message.getContent(), Json.write(message.getAttachment()), message.getReplyToId(),
                            message.isForwarded(), reactionsJson(message.getReactions()), message.isEdited(),
                            message.getEditedAt(), message.isDeleted(), message.getDeletedAt(),
                            Json.write(message.getMetadata()), message.getCreatedAt()),
                    insertMessageId.bind(tenantId, conversationId, id, seq)));
            advanceReadState(tenantId, conversationId, senderId, seq, seq);
            count(tenantId, "messages");
            ChatMessage stored = Json.convert(message, ChatMessage.class);
            stored.setSeq(seq);
            return new AppendResult(stored, false);
        });
    }

    /**
     * Compare-and-set loop on {@code conversations.last_seq}; also sets the last-message fields in
     * the same Paxos round. Returns -1 when the conversation does not exist.
     */
    private long allocateSeq(ChatMessage m) {
        String tenantId = m.getTenantId();
        String conversationId = m.getConversationId();
        Row current = session.execute(selectLastSeq.bind(tenantId, conversationId)).one();
        if (current == null) {
            return -1;
        }
        long expected = current.isNull("last_seq") ? 0 : current.getLong("last_seq");
        String preview = MessagePreview.of(m);
        for (int attempt = 0; attempt < MAX_SEQ_ATTEMPTS; attempt++) {
            long next = expected + 1;
            try {
                ResultSet rs = session.execute(casLastSeq.bind(next, m.getId(), preview, m.getSenderId(),
                        m.getCreatedAt(), m.getCreatedAt(), tenantId, conversationId, expected));
                if (rs.wasApplied()) {
                    return next;
                }
                Row row = rs.one();
                if (row == null || !row.getColumnDefinitions().contains("last_seq")) {
                    return -1;
                }
                expected = row.isNull("last_seq") ? 0 : row.getLong("last_seq");
            } catch (WriteTimeoutException e) {
                if (e.getWriteType() != DefaultWriteType.CAS) {
                    throw e;
                }
                // Outcome unknown: a serial read tells whether our proposal was committed.
                Row row = session.execute(selectLastSeq.bind(tenantId, conversationId)
                        .setConsistencyLevel(DefaultConsistencyLevel.SERIAL)).one();
                if (row == null) {
                    return -1;
                }
                if (m.getId().equals(row.getString("last_message_id"))) {
                    return row.getLong("last_seq");
                }
                expected = row.isNull("last_seq") ? 0 : row.getLong("last_seq");
            }
            if (attempt >= 2) {
                StoreSupport.backoff(COMPONENT, ThreadLocalRandom.current().nextLong(1, Math.min(50, 2L * attempt) + 1));
            }
        }
        log.warn("Could not allocate a sequence number for conversation {} after {} contended attempts",
                conversationId, MAX_SEQ_ATTEMPTS);
        throw new InfrastructureUnavailableException(COMPONENT, "too much write contention on the conversation");
    }

    @Override
    public Optional<ChatMessage> findMessage(String tenantId, String conversationId, String messageId) {
        return call(() -> {
            Row index = session.execute(selectMessageSeq.bind(tenantId, conversationId, messageId)).one();
            if (index == null) {
                return Optional.empty();
            }
            long seq = index.getLong("seq");
            return Optional.ofNullable(toMessage(session.execute(
                    selectMessage.bind(tenantId, conversationId, seq / BUCKET_SIZE, seq)).one()));
        });
    }

    @Override
    public List<ChatMessage> listMessages(String tenantId, String conversationId, Long afterSeq, Long beforeSeq, int limit) {
        return call(() -> {
            if (limit <= 0) {
                return List.of();
            }
            Row conversation = session.execute(selectLastSeq.bind(tenantId, conversationId)).one();
            if (conversation == null || conversation.isNull("last_seq")) {
                return List.of();
            }
            long lastSeq = conversation.getLong("last_seq");
            List<ChatMessage> result = new ArrayList<>();
            if (afterSeq != null) {
                for (long bucket = Math.max(0, afterSeq + 1) / BUCKET_SIZE; bucket <= lastSeq / BUCKET_SIZE
                        && result.size() < limit; bucket++) {
                    for (Row row : session.execute(selectMessagesAfter.bind(tenantId, conversationId, bucket, afterSeq,
                            limit - result.size()))) {
                        result.add(toMessage(row));
                    }
                }
                return result;
            }
            long upper = beforeSeq == null ? lastSeq + 1 : Math.min(beforeSeq, lastSeq + 1);
            for (long bucket = (upper - 1) / BUCKET_SIZE; upper > 1 && bucket >= 0 && result.size() < limit; bucket--) {
                for (Row row : session.execute(selectMessagesBefore.bind(tenantId, conversationId, bucket, upper,
                        limit - result.size()))) {
                    result.add(toMessage(row));
                }
            }
            Collections.reverse(result);
            return result;
        });
    }

    @Override
    public void updateMessage(ChatMessage message) {
        run(() -> {
            String tenantId = message.getTenantId();
            String conversationId = message.getConversationId();
            Row index = session.execute(selectMessageSeq.bind(tenantId, conversationId, message.getId())).one();
            if (index == null) {
                return;
            }
            long seq = index.getLong("seq");
            session.execute(updateMessage.bind(message.getContent(), Json.write(message.getAttachment()),
                    reactionsJson(message.getReactions()), message.isEdited(), message.getEditedAt(), message.isDeleted(),
                    message.getDeletedAt(), Json.write(message.getMetadata()), tenantId, conversationId,
                    seq / BUCKET_SIZE, seq));
        });
    }

    // ------------------------------------------------------------------ admin

    @Override
    public StoreStats stats(String tenantId) {
        return call(() -> {
            Map<String, Long> counts = counters(tenantId == null ? GLOBAL_SCOPE : tenantScope(tenantId));
            return new StoreStats(counts.getOrDefault("users", 0L), counts.getOrDefault("conversations", 0L),
                    counts.getOrDefault("messages", 0L));
        });
    }

    private static String tenantScope(String tenantId) {
        return "t:" + tenantId;
    }

    /** Best-effort: increments the tenant and global counters on a random shard to avoid hot partitions. */
    private void count(String tenantId, String name) {
        int shard = ThreadLocalRandom.current().nextInt(COUNTER_SHARDS);
        try {
            session.execute(BatchStatement.newInstance(BatchType.COUNTER,
                    incrementCounter.bind(1L, tenantScope(tenantId), shard, name),
                    incrementCounter.bind(1L, GLOBAL_SCOPE, shard, name)));
        } catch (DriverException e) {
            log.warn("Could not update {} counter for tenant {}: {}", name, tenantId, e.getMessage());
        }
    }

    private Map<String, Long> counters(String scope) {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (Row row : session.execute(selectCounters.bind(scope, SHARDS))) {
            totals.merge(row.getString("name"), row.getLong("total"), Long::sum);
        }
        return totals;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Race-safe "first writer wins" on an {@code INSERT ... IF NOT EXISTS} claim row. Returns empty
     * when this caller owns the claim; otherwise the winner's result once visible. A claim left by a
     * writer that died mid-way is taken over by compare-and-set after {@link #CLAIM_STALE_MILLIS}.
     */
    private <T> Optional<T> claim(Supplier<ResultSet> insert, Predicate<String> takeover, String ownerColumn,
                                  String ownerId, Function<String, Optional<T>> lookup) {
        for (int attempt = 0; ; attempt++) {
            ResultSet rs = insert.get();
            if (rs.wasApplied()) {
                return Optional.empty();
            }
            Row existing = rs.one();
            String owner = existing == null ? null : existing.getString(ownerColumn);
            if (owner == null) {
                continue;
            }
            Optional<T> result = lookup.apply(owner);
            if (result.isPresent()) {
                return result;
            }
            if (owner.equals(ownerId)) {
                return Optional.empty(); // our own earlier attempt claimed it but never finished
            }
            Instant claimedAt = existing.getInstant("claimed_at");
            if (claimedAt == null || claimedAt.isBefore(Instant.now().minusMillis(CLAIM_STALE_MILLIS))) {
                if (takeover.test(owner)) {
                    log.warn("Took over abandoned claim from {}", owner);
                    return Optional.empty();
                }
                continue;
            }
            StoreSupport.backoff(COMPONENT, Math.min(200, 5L << Math.min(attempt, 5)));
        }
    }

    /** Binds values, leaving nulls unset so inserts do not create tombstones. */
    private static BoundStatement bindUnsetNulls(PreparedStatement statement, Object... values) {
        BoundStatement bound = statement.bind(values);
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                bound = bound.unset(i);
            }
        }
        return bound;
    }

    private <K, V> List<V> parallel(List<K> keys, Function<K, CompletionStage<V>> call) {
        List<V> results = new ArrayList<>(keys.size());
        for (int from = 0; from < keys.size(); from += PARALLELISM) {
            List<CompletableFuture<V>> window = keys.subList(from, Math.min(keys.size(), from + PARALLELISM)).stream()
                    .map(k -> call.apply(k).toCompletableFuture())
                    .toList();
            for (CompletableFuture<V> future : window) {
                results.add(join(future));
            }
        }
        return results;
    }

    private static void awaitAll(List<? extends CompletionStage<?>> stages) {
        for (CompletionStage<?> stage : stages) {
            join(stage.toCompletableFuture());
        }
    }

    private static <V> V join(CompletableFuture<V> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------ mapping

    private static long longOrZero(Row row, String column) {
        return row.isNull(column) ? 0L : row.getLong(column);
    }

    private static ChatUser toUser(Row row) {
        if (row == null || row.isNull("created_at")) {
            return null; // missing, or a partial row left by updateLastSeen on an unknown user
        }
        return ChatUser.builder()
                .tenantId(row.getString("tenant_id"))
                .id(row.getString("id"))
                .displayName(row.getString("display_name"))
                .email(row.getString("email"))
                .phone(row.getString("phone"))
                .avatarUrl(row.getString("avatar_url"))
                .about(row.getString("about"))
                .pushTokens(new LinkedHashSet<>(row.getList("push_tokens", String.class)))
                .metadata(Json.readMap(row.getString("metadata")))
                .deactivated(row.getBoolean("deactivated"))
                .createdAt(row.getInstant("created_at"))
                .updatedAt(row.getInstant("updated_at"))
                .lastSeenAt(row.getInstant("last_seen_at"))
                .build();
    }

    private static Conversation toConversation(Row row) {
        if (row == null || row.isNull("type")) {
            return null;
        }
        return Conversation.builder()
                .tenantId(row.getString("tenant_id"))
                .id(row.getString("id"))
                .type(Conversation.Type.valueOf(row.getString("type")))
                .directKey(row.getString("direct_key"))
                .title(row.getString("title"))
                .description(row.getString("description"))
                .avatarUrl(row.getString("avatar_url"))
                .createdBy(row.getString("created_by"))
                .lastSeq(longOrZero(row, "last_seq"))
                .lastMessageId(row.getString("last_message_id"))
                .lastMessagePreview(row.getString("last_message_preview"))
                .lastMessageSenderId(row.getString("last_message_sender_id"))
                .lastMessageAt(row.getInstant("last_message_at"))
                .metadata(Json.readMap(row.getString("metadata")))
                .createdAt(row.getInstant("created_at"))
                .updatedAt(row.getInstant("updated_at"))
                .build();
    }

    private static Participant toParticipant(Row row) {
        if (row == null || row.isNull("role")) {
            return null; // missing, or a partial row left by a receipt for a non-member
        }
        return Participant.builder()
                .tenantId(row.getString("tenant_id"))
                .conversationId(row.getString("conversation_id"))
                .userId(row.getString("user_id"))
                .role(Participant.Role.valueOf(row.getString("role")))
                .joinedSeq(longOrZero(row, "joined_seq"))
                .deliveredSeq(longOrZero(row, "delivered_seq"))
                .readSeq(longOrZero(row, "read_seq"))
                .muted(row.getBoolean("muted"))
                .archived(row.getBoolean("archived"))
                .pinned(row.getBoolean("pinned"))
                .joinedAt(row.getInstant("joined_at"))
                .leftAt(row.getInstant("left_at"))
                .updatedAt(row.getInstant("updated_at"))
                .build();
    }

    private static ChatMessage toMessage(Row row) {
        if (row == null) {
            return null;
        }
        return ChatMessage.builder()
                .tenantId(row.getString("tenant_id"))
                .id(row.getString("id"))
                .conversationId(row.getString("conversation_id"))
                .seq(row.getLong("seq"))
                .senderId(row.getString("sender_id"))
                .clientMsgId(row.getString("client_msg_id"))
                .type(ChatMessage.Type.valueOf(row.getString("type")))
                .content(row.getString("content"))
                .attachment(Json.read(row.getString("attachment"), ChatMessage.Attachment.class))
                .replyToId(row.getString("reply_to_id"))
                .forwarded(row.getBoolean("forwarded"))
                .reactions(toReactions(row.getString("reactions")))
                .edited(row.getBoolean("edited"))
                .editedAt(row.getInstant("edited_at"))
                .deleted(row.getBoolean("deleted"))
                .deletedAt(row.getInstant("deleted_at"))
                .metadata(Json.readMap(row.getString("metadata")))
                .createdAt(row.getInstant("created_at"))
                .build();
    }

    private static String reactionsJson(Map<String, Set<String>> reactions) {
        if (reactions == null || reactions.isEmpty()) {
            return null;
        }
        List<Reaction> list = new ArrayList<>();
        reactions.forEach((emoji, userIds) -> {
            if (userIds != null && !userIds.isEmpty()) {
                list.add(new Reaction(emoji, new ArrayList<>(userIds)));
            }
        });
        return list.isEmpty() ? null : Json.write(list);
    }

    private static Map<String, Set<String>> toReactions(String json) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        List<Reaction> list = Json.read(json, REACTIONS);
        if (list != null) {
            list.forEach(r -> result.put(r.emoji(), new LinkedHashSet<>(r.userIds())));
        }
        return result;
    }
}
