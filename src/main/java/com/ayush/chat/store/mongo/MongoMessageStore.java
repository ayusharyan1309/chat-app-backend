package com.ayush.chat.store.mongo;

import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.exception.DomainException;
import com.ayush.chat.core.exception.InfrastructureUnavailableException;
import com.ayush.chat.core.util.Json;
import com.ayush.chat.store.MessagePreview;
import com.ayush.chat.store.MessageStore;
import com.ayush.chat.store.StoreSupport;
import com.ayush.chat.store.model.AppendResult;
import com.ayush.chat.store.model.ChatMessage;
import com.ayush.chat.store.model.ChatUser;
import com.ayush.chat.store.model.Conversation;
import com.ayush.chat.store.model.InboxItem;
import com.ayush.chat.store.model.Participant;
import com.ayush.chat.store.model.StoreStats;
import com.mongodb.ConnectionString;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Filters.regex;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.client.model.Sorts.ascending;
import static com.mongodb.client.model.Sorts.descending;
import static com.mongodb.client.model.Sorts.orderBy;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.inc;
import static com.mongodb.client.model.Updates.max;
import static com.mongodb.client.model.Updates.set;
import static com.mongodb.client.model.Updates.setOnInsert;

/**
 * {@link MessageStore} on MongoDB (and wire-compatible engines). Uses only single-document
 * atomic operations, so it needs neither a replica set nor multi-document transactions:
 * <ul>
 *   <li>seq allocation is one {@code findOneAndUpdate} doing {@code $inc lastSeq} and setting the
 *       last-message fields in the same atomic step;</li>
 *   <li>idempotent sends and DIRECT conversations are serialised by inserting a claim document
 *       with a deterministic {@code _id} <em>before</em> any other write, so a lost race never
 *       burns a seq or creates a second conversation;</li>
 *   <li>the inbox is driven by the user's participant documents joined to conversations on a
 *       denormalised {@code activityAt}; messages never fan out writes to participants.</li>
 * </ul>
 * Every document carries {@code tenantId} and a tenant-qualified {@code _id}. Instants are BSON
 * dates (millisecond precision). Metadata is stored as JSON text because its keys are
 * tenant-defined and may contain characters ({@code .}, {@code $}) that some Mongo-compatible
 * engines reject in field names.
 * <p>
 * Driver failures in data operations, and calls after {@link #close()}, surface as
 * {@link InfrastructureUnavailableException} (503).
 */
@Slf4j
public class MongoMessageStore implements MessageStore {

    private static final String COMPONENT = ConnectorKind.MESSAGE_STORE.getDisplayName();

    /** A claim whose owner has not finished after this long is considered abandoned and may be taken over. */
    private static final long CLAIM_STALE_MILLIS = 10_000;
    /** Max ids per {@code $in} query when joining the inbox. */
    private static final int IN_BATCH = 1_000;

    private final MongoClient client;
    private final MongoDatabase db;
    private final String databaseName;
    private final String prefix;
    private final MongoCollection<Document> users;
    private final MongoCollection<Document> blocks;
    private final MongoCollection<Document> conversations;
    private final MongoCollection<Document> directIndex;
    private final MongoCollection<Document> participants;
    private final MongoCollection<Document> messages;
    private final MongoCollection<Document> clientMsgIndex;
    private final AtomicBoolean closed = new AtomicBoolean();

    public MongoMessageStore(String uri, String database, String collectionPrefix) {
        ConnectionString connection;
        try {
            connection = new ConnectionString(uri);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid MongoDB connection string: " + e.getMessage(), e);
        }
        MongoClientSettings.Builder settings = MongoClientSettings.builder().applyConnectionString(connection);
        if (connection.getApplicationName() == null) {
            settings.applicationName("chat-platform");
        }
        if (connection.getServerSelectionTimeout() == null) {
            settings.applyToClusterSettings(b -> b.serverSelectionTimeout(10, TimeUnit.SECONDS));
        }
        this.databaseName = database;
        this.prefix = collectionPrefix == null ? "" : collectionPrefix;
        this.client = MongoClients.create(settings.build());
        this.db = client.getDatabase(database);
        this.users = db.getCollection(prefix + "users");
        this.blocks = db.getCollection(prefix + "blocks");
        this.conversations = db.getCollection(prefix + "conversations");
        this.directIndex = db.getCollection(prefix + "direct_conversations");
        this.participants = db.getCollection(prefix + "participants");
        this.messages = db.getCollection(prefix + "messages");
        this.clientMsgIndex = db.getCollection(prefix + "client_msg_ids");
        try {
            db.runCommand(new Document("ping", 1));
            createIndexes();
        } catch (RuntimeException e) {
            client.close();
            throw new IllegalStateException("Could not initialise MongoDB store on " + connection.getHosts()
                    + "/" + database + ": " + e.getMessage(), e);
        }
    }

    private void createIndexes() {
        users.createIndex(Indexes.ascending("tenantId", "userId"));
        blocks.createIndex(Indexes.ascending("tenantId", "blockerId", "blockedId"));
        conversations.createIndex(Indexes.compoundIndex(Indexes.ascending("tenantId"), Indexes.descending("activityAt")));
        participants.createIndex(Indexes.ascending("tenantId", "conversationId", "userId"));
        participants.createIndex(Indexes.ascending("tenantId", "userId", "leftAt"));
        messages.createIndex(Indexes.ascending("tenantId", "conversationId", "seq"), new IndexOptions().unique(true));
    }

    /**
     * Injective composite key: parts joined by {@code |} with {@code |} and {@code \} escaped, so
     * ids containing the separator can never collide across tenants, users or client ids.
     */
    static String key(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = Objects.requireNonNull(parts[i], "key part");
            if (i > 0) {
                sb.append('|');
            }
            for (int j = 0; j < part.length(); j++) {
                char ch = part.charAt(j);
                if (ch == '|' || ch == '\\') {
                    sb.append('\\');
                }
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** Runs one data operation: driver failures (and use after close) become a retryable 503. */
    private <T> T call(Supplier<T> operation) {
        if (closed.get()) {
            throw new InfrastructureUnavailableException(COMPONENT, "store closed");
        }
        try {
            return operation.get();
        } catch (MongoException e) {
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
        return "mongodb";
    }

    @Override
    public HealthStatus health() {
        try {
            db.runCommand(new Document("ping", 1));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("database", databaseName);
            details.put("collectionPrefix", prefix);
            try {
                Object version = db.runCommand(new Document("buildInfo", 1)).get("version");
                if (version != null) {
                    details.put("serverVersion", String.valueOf(version));
                }
            } catch (MongoException e) {
                // buildInfo is optional on some managed / compatible engines
            }
            return HealthStatus.up("MongoDB reachable", details);
        } catch (RuntimeException e) {
            return HealthStatus.down("MongoDB unreachable: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                client.close();
            } catch (RuntimeException e) {
                log.warn("Error closing MongoDB client: {}", e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ users

    @Override
    public ChatUser upsertUser(ChatUser user) {
        return call(() -> {
            List<Bson> updates = new ArrayList<>(List.of(
                    set("tenantId", user.getTenantId()),
                    set("userId", user.getId()),
                    set("displayName", user.getDisplayName()),
                    set("email", user.getEmail()),
                    set("phone", user.getPhone()),
                    set("avatarUrl", user.getAvatarUrl()),
                    set("about", user.getAbout()),
                    set("pushTokens", user.getPushTokens() == null ? List.of() : new ArrayList<>(user.getPushTokens())),
                    set("metadata", Json.write(user.getMetadata())),
                    set("deactivated", user.isDeactivated()),
                    set("updatedAt", date(user.getUpdatedAt())),
                    setOnInsert("createdAt", date(user.getCreatedAt()))));
            if (user.getLastSeenAt() != null) {
                updates.add(set("lastSeenAt", date(user.getLastSeenAt())));
            }
            Bson filter = eq("_id", key(user.getTenantId(), user.getId()));
            FindOneAndUpdateOptions options = new FindOneAndUpdateOptions().upsert(true)
                    .returnDocument(ReturnDocument.AFTER);
            Document stored = retryOnDuplicateKey(() -> users.findOneAndUpdate(filter, combine(updates), options));
            return toUser(stored);
        });
    }

    @Override
    public Optional<ChatUser> findUser(String tenantId, String userId) {
        return call(() -> Optional.ofNullable(users.find(eq("_id", key(tenantId, userId))).first())
                .map(MongoMessageStore::toUser));
    }

    @Override
    public List<ChatUser> findUsers(String tenantId, Collection<String> userIds) {
        return call(() -> {
            if (userIds.isEmpty()) {
                return List.of();
            }
            Map<String, ChatUser> found = new LinkedHashMap<>();
            users.find(in("_id", userIds.stream().distinct().map(id -> key(tenantId, id)).toList()))
                    .forEach(d -> found.put(d.getString("userId"), toUser(d)));
            return userIds.stream().distinct().map(found::get).filter(Objects::nonNull).toList();
        });
    }

    @Override
    public List<ChatUser> searchUsers(String tenantId, String query, int offset, int limit) {
        return call(() -> {
            if (limit <= 0) {
                return List.of();
            }
            Bson filter = eq("tenantId", tenantId);
            String q = query == null ? "" : query.trim();
            if (!q.isEmpty()) {
                String pattern = escapeRegex(q);
                filter = and(filter, or(regex("userId", pattern, "i"), regex("displayName", pattern, "i"),
                        regex("email", pattern, "i")));
            }
            List<ChatUser> result = new ArrayList<>();
            users.find(filter).sort(ascending("userId")).skip(Math.max(0, offset)).limit(limit)
                    .forEach(d -> result.add(toUser(d)));
            return result;
        });
    }

    /** Escapes ASCII punctuation so the query is matched literally (works for PCRE and Java regex engines). */
    static String escapeRegex(String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (cp < 128 && !Character.isLetterOrDigit(cp) && !Character.isWhitespace(cp)) {
                sb.append('\\');
            }
            sb.appendCodePoint(cp);
        });
        return sb.toString();
    }

    @Override
    public long countUsers(String tenantId) {
        return call(() -> users.countDocuments(eq("tenantId", tenantId)));
    }

    @Override
    public void updateLastSeen(String tenantId, String userId, Instant lastSeenAt) {
        run(() -> users.updateOne(eq("_id", key(tenantId, userId)), set("lastSeenAt", date(lastSeenAt))));
    }

    // ------------------------------------------------------------------ blocks

    @Override
    public void setBlocked(String tenantId, String blockerId, String blockedId, boolean blocked) {
        run(() -> {
            Bson filter = eq("_id", key(tenantId, blockerId, blockedId));
            if (!blocked) {
                blocks.deleteOne(filter);
                return;
            }
            retryOnDuplicateKey(() -> blocks.updateOne(filter, combine(setOnInsert("tenantId", tenantId),
                    setOnInsert("blockerId", blockerId), setOnInsert("blockedId", blockedId),
                    setOnInsert("createdAt", new Date())), new UpdateOptions().upsert(true)));
        });
    }

    @Override
    public boolean isBlocked(String tenantId, String blockerId, String blockedId) {
        return call(() -> blocks.find(eq("_id", key(tenantId, blockerId, blockedId))).projection(include("_id"))
                .first() != null);
    }

    @Override
    public List<String> listBlocked(String tenantId, String blockerId) {
        return call(() -> {
            List<String> result = new ArrayList<>();
            blocks.find(and(eq("tenantId", tenantId), eq("blockerId", blockerId)))
                    .projection(include("blockedId")).sort(ascending("blockedId"))
                    .forEach(d -> result.add(d.getString("blockedId")));
            return result;
        });
    }

    // ------------------------------------------------------------------ conversations

    @Override
    public Conversation createConversation(Conversation conversation, List<Participant> initial) {
        return call(() -> {
            String tenantId = conversation.getTenantId();
            if (conversation.getType() == Conversation.Type.DIRECT) {
                Optional<Conversation> existing = claim(directIndex, key(tenantId, conversation.getDirectKey()),
                        "conversationId", conversation.getId(), id -> findConversation(tenantId, id));
                if (existing.isPresent()) {
                    return existing.get();
                }
            }
            // Participants go in before the conversation so that anyone who can see the conversation
            // (including a losing concurrent DIRECT creator) also sees its members. Orphaned
            // participant rows without a conversation are invisible to every query.
            if (!initial.isEmpty()) {
                List<ReplaceOneModel<Document>> writes = initial.stream()
                        .map(p -> new ReplaceOneModel<>(eq("_id", participantId(p)), participantDoc(p),
                                new ReplaceOptions().upsert(true)))
                        .toList();
                retryOnDuplicateKey(() -> participants.bulkWrite(writes));
            }
            Document doc = conversationDoc(conversation);
            try {
                conversations.insertOne(doc);
            } catch (MongoException e) {
                if (!isDuplicateKey(e)) {
                    throw e;
                }
                // same id created twice (caller retry): return what is stored, never reset lastSeq
                return findConversation(tenantId, conversation.getId()).orElseThrow(() -> e);
            }
            return toConversation(doc);
        });
    }

    @Override
    public Optional<Conversation> findConversation(String tenantId, String conversationId) {
        return call(() -> Optional.ofNullable(conversations.find(eq("_id", key(tenantId, conversationId))).first())
                .map(MongoMessageStore::toConversation));
    }

    @Override
    public Optional<Conversation> findDirectConversation(String tenantId, String directKey) {
        return call(() -> {
            Document index = directIndex.find(eq("_id", key(tenantId, directKey))).first();
            return index == null ? Optional.empty() : findConversation(tenantId, index.getString("conversationId"));
        });
    }

    @Override
    public void updateConversation(Conversation conversation) {
        run(() -> conversations.updateOne(eq("_id", key(conversation.getTenantId(), conversation.getId())), combine(
                set("title", conversation.getTitle()),
                set("description", conversation.getDescription()),
                set("avatarUrl", conversation.getAvatarUrl()),
                set("metadata", Json.write(conversation.getMetadata())),
                set("updatedAt", date(conversation.getUpdatedAt())))));
    }

    @Override
    public List<InboxItem> listInbox(String tenantId, String userId, int limit, Instant before) {
        return call(() -> {
            if (limit <= 0) {
                return List.of();
            }
            Map<String, Participant> memberships = new LinkedHashMap<>();
            participants.find(and(eq("tenantId", tenantId), eq("userId", userId), eq("leftAt", null)))
                    .forEach(d -> memberships.put(key(tenantId, d.getString("conversationId")), toParticipant(d)));
            if (memberships.isEmpty()) {
                return List.of();
            }
            Bson sort = orderBy(descending("activityAt"), ascending("conversationId"));
            List<Conversation> found = new ArrayList<>();
            List<String> ids = new ArrayList<>(memberships.keySet());
            for (int from = 0; from < ids.size(); from += IN_BATCH) {
                Bson filter = in("_id", ids.subList(from, Math.min(ids.size(), from + IN_BATCH)));
                if (before != null) {
                    filter = and(filter, lt("activityAt", new Date(ceilMillis(before))));
                }
                conversations.find(filter).sort(sort).limit(limit).forEach(d -> found.add(toConversation(d)));
            }
            found.sort(Comparator.comparing(Conversation::activityAt).reversed().thenComparing(Conversation::getId));
            List<InboxItem> items = new ArrayList<>();
            for (Conversation c : found.subList(0, Math.min(limit, found.size()))) {
                items.add(new InboxItem(c, memberships.get(key(tenantId, c.getId()))));
            }
            return items;
        });
    }

    /**
     * Stored dates are millisecond-truncated, so {@code stored < before} is evaluated against
     * {@code before} rounded up to the next millisecond to keep the cursor exact.
     */
    private static long ceilMillis(Instant instant) {
        long millis = instant.toEpochMilli();
        return instant.getNano() % 1_000_000 == 0 ? millis : millis + 1;
    }

    // ------------------------------------------------------------------ participants

    @Override
    public List<Participant> listParticipants(String tenantId, String conversationId) {
        return call(() -> {
            List<Participant> result = new ArrayList<>();
            participants.find(and(eq("tenantId", tenantId), eq("conversationId", conversationId), eq("leftAt", null)))
                    .sort(ascending("userId"))
                    .forEach(d -> result.add(toParticipant(d)));
            return result;
        });
    }

    @Override
    public Optional<Participant> findParticipant(String tenantId, String conversationId, String userId) {
        return call(() -> Optional.ofNullable(participants.find(eq("_id", key(tenantId, conversationId, userId))).first())
                .map(MongoMessageStore::toParticipant));
    }

    @Override
    public void upsertParticipant(Participant participant) {
        run(() -> {
            // $max keeps watermarks monotonic; everything else is overwritten.
            Document doc = participantDoc(participant);
            doc.remove("_id");
            long delivered = (Long) doc.remove("deliveredSeq");
            long read = (Long) doc.remove("readSeq");
            List<Bson> updates = new ArrayList<>();
            doc.forEach((field, value) -> updates.add(set(field, value)));
            updates.add(max("deliveredSeq", delivered));
            updates.add(max("readSeq", read));
            retryOnDuplicateKey(() -> participants.updateOne(eq("_id", participantId(participant)), combine(updates),
                    new UpdateOptions().upsert(true)));
        });
    }

    @Override
    public void removeParticipant(String tenantId, String conversationId, String userId, Instant at) {
        run(() -> participants.updateOne(eq("_id", key(tenantId, conversationId, userId)),
                combine(set("leftAt", date(at)), set("updatedAt", date(at)))));
    }

    @Override
    public void advanceReadState(String tenantId, String conversationId, String userId, Long deliveredSeq, Long readSeq) {
        run(() -> {
            Long delivered = deliveredSeq == null ? readSeq
                    : readSeq == null ? deliveredSeq : Math.max(deliveredSeq, readSeq);
            if (delivered == null) {
                return;
            }
            List<Bson> updates = new ArrayList<>();
            updates.add(max("deliveredSeq", delivered));
            if (readSeq != null) {
                updates.add(max("readSeq", readSeq));
            }
            updates.add(set("updatedAt", new Date()));
            participants.updateOne(eq("_id", key(tenantId, conversationId, userId)), combine(updates));
        });
    }

    // ------------------------------------------------------------------ messages

    @Override
    public AppendResult appendMessage(ChatMessage message) {
        return call(() -> {
            String tenantId = message.getTenantId();
            String conversationId = message.getConversationId();
            String claimId = null;
            if (message.getClientMsgId() != null) {
                claimId = key(tenantId, conversationId, message.getSenderId(), message.getClientMsgId());
                Optional<ChatMessage> original = claim(clientMsgIndex, claimId, "messageId", message.getId(),
                        id -> findMessage(tenantId, conversationId, id));
                if (original.isPresent()) {
                    return new AppendResult(original.get(), true);
                }
            }
            Date at = date(message.getCreatedAt());
            Document conversation = conversations.findOneAndUpdate(eq("_id", key(tenantId, conversationId)),
                    combine(inc("lastSeq", 1L),
                            set("lastMessageId", message.getId()),
                            set("lastMessagePreview", MessagePreview.of(message)),
                            set("lastMessageSenderId", message.getSenderId()),
                            set("lastMessageAt", at),
                            set("activityAt", at),
                            set("updatedAt", at)),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER).projection(include("lastSeq")));
            if (conversation == null) {
                if (claimId != null) {
                    clientMsgIndex.deleteOne(and(eq("_id", claimId), eq("messageId", message.getId())));
                }
                throw DomainException.notFound("Conversation");
            }
            long seq = conversation.get("lastSeq", Number.class).longValue();
            Document doc = messageDoc(message, seq);
            messages.insertOne(doc);
            participants.updateOne(eq("_id", key(tenantId, conversationId, message.getSenderId())),
                    combine(max("deliveredSeq", seq), max("readSeq", seq), set("updatedAt", new Date())));
            return new AppendResult(toMessage(doc), false);
        });
    }

    @Override
    public Optional<ChatMessage> findMessage(String tenantId, String conversationId, String messageId) {
        return call(() -> Optional.ofNullable(messages.find(eq("_id", key(tenantId, conversationId, messageId))).first())
                .map(MongoMessageStore::toMessage));
    }

    @Override
    public List<ChatMessage> listMessages(String tenantId, String conversationId, Long afterSeq, Long beforeSeq, int limit) {
        return call(() -> {
            if (limit <= 0) {
                return List.of();
            }
            Bson scope = and(eq("tenantId", tenantId), eq("conversationId", conversationId));
            List<ChatMessage> result = new ArrayList<>();
            if (afterSeq != null) {
                messages.find(and(scope, gt("seq", afterSeq))).sort(ascending("seq")).limit(limit)
                        .forEach(d -> result.add(toMessage(d)));
                return result;
            }
            Bson filter = beforeSeq == null ? scope : and(scope, lt("seq", beforeSeq));
            messages.find(filter).sort(descending("seq")).limit(limit).forEach(d -> result.add(toMessage(d)));
            Collections.reverse(result);
            return result;
        });
    }

    @Override
    public void updateMessage(ChatMessage message) {
        Bson id = eq("_id", key(message.getTenantId(), message.getConversationId(), message.getId()));
        run(() -> messages.updateOne(id, combine(
                set("content", message.getContent()),
                set("attachment", attachmentDoc(message.getAttachment())),
                set("reactions", reactionsDoc(message.getReactions())),
                set("edited", message.isEdited()),
                set("editedAt", date(message.getEditedAt())),
                set("deleted", message.isDeleted()),
                set("deletedAt", date(message.getDeletedAt())),
                set("metadata", Json.write(message.getMetadata())))));
    }

    // ------------------------------------------------------------------ admin

    @Override
    public StoreStats stats(String tenantId) {
        return call(() -> {
            if (tenantId == null) {
                return new StoreStats(users.estimatedDocumentCount(), conversations.estimatedDocumentCount(),
                        messages.estimatedDocumentCount());
            }
            Bson filter = eq("tenantId", tenantId);
            return new StoreStats(users.countDocuments(filter), conversations.countDocuments(filter),
                    messages.countDocuments(filter));
        });
    }

    // ------------------------------------------------------------------ claims

    /**
     * Race-safe "first writer wins" on a deterministic {@code _id}. Returns empty when this caller
     * now owns the claim and must perform the write; otherwise returns the winner's result.
     * If the claim exists but the winner's result is not visible yet, polls briefly; a claim left
     * behind by a writer that died mid-way is taken over (compare-and-set on the stale owner) once
     * it is older than {@link #CLAIM_STALE_MILLIS}.
     */
    private <T> Optional<T> claim(MongoCollection<Document> index, String claimId, String ownerField, String ownerId,
                                  Function<String, Optional<T>> lookup) {
        for (int attempt = 0; ; attempt++) {
            try {
                index.insertOne(new Document("_id", claimId).append(ownerField, ownerId).append("claimedAt", new Date()));
                return Optional.empty();
            } catch (MongoException e) {
                if (!isDuplicateKey(e)) {
                    throw e;
                }
            }
            Document existing = index.find(eq("_id", claimId)).first();
            if (existing == null) {
                continue; // released in between (e.g. conversation missing); try to claim again
            }
            String owner = existing.getString(ownerField);
            Optional<T> result = lookup.apply(owner);
            if (result.isPresent()) {
                return result;
            }
            if (owner.equals(ownerId)) {
                return Optional.empty(); // our own earlier attempt claimed it but never finished
            }
            Date claimedAt = existing.getDate("claimedAt");
            if (claimedAt == null || System.currentTimeMillis() - claimedAt.getTime() > CLAIM_STALE_MILLIS) {
                long taken = index.updateOne(and(eq("_id", claimId), eq(ownerField, owner)),
                        combine(set(ownerField, ownerId), set("claimedAt", new Date()))).getModifiedCount();
                if (taken == 1) {
                    log.warn("Took over abandoned claim {} from {}", claimId, owner);
                    return Optional.empty();
                }
                continue;
            }
            StoreSupport.backoff(COMPONENT, Math.min(200, 5L << Math.min(attempt, 5)));
        }
    }

    private static boolean isDuplicateKey(MongoException e) {
        return ErrorCategory.fromErrorCode(e.getCode()) == ErrorCategory.DUPLICATE_KEY
                || (e instanceof MongoBulkWriteException bulk && bulk.getWriteErrors().stream()
                .anyMatch(w -> ErrorCategory.fromErrorCode(w.getCode()) == ErrorCategory.DUPLICATE_KEY));
    }

    /** Two concurrent upserts on the same {@code _id} may race to insert; the loser retries as an update. */
    private static <T> T retryOnDuplicateKey(Supplier<T> write) {
        try {
            return write.get();
        } catch (MongoException e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
            return write.get();
        }
    }

    // ------------------------------------------------------------------ mapping

    private static Date date(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private static Instant instant(Document d, String field) {
        Date value = d.getDate(field);
        return value == null ? null : value.toInstant();
    }

    private static long longValue(Document d, String field) {
        Number value = d.get(field, Number.class);
        return value == null ? 0L : value.longValue();
    }

    private static boolean bool(Document d, String field) {
        return Boolean.TRUE.equals(d.getBoolean(field));
    }

    private static Map<String, Object> metadata(Document d) {
        return Json.readMap(d.getString("metadata"));
    }

    private static ChatUser toUser(Document d) {
        List<String> tokens = d.getList("pushTokens", String.class);
        return ChatUser.builder()
                .tenantId(d.getString("tenantId"))
                .id(d.getString("userId"))
                .displayName(d.getString("displayName"))
                .email(d.getString("email"))
                .phone(d.getString("phone"))
                .avatarUrl(d.getString("avatarUrl"))
                .about(d.getString("about"))
                .pushTokens(tokens == null ? new LinkedHashSet<>() : new LinkedHashSet<>(tokens))
                .metadata(metadata(d))
                .deactivated(bool(d, "deactivated"))
                .createdAt(instant(d, "createdAt"))
                .updatedAt(instant(d, "updatedAt"))
                .lastSeenAt(instant(d, "lastSeenAt"))
                .build();
    }

    private static Document conversationDoc(Conversation c) {
        return new Document("_id", key(c.getTenantId(), c.getId()))
                .append("tenantId", c.getTenantId())
                .append("conversationId", c.getId())
                .append("type", c.getType() == null ? null : c.getType().name())
                .append("directKey", c.getDirectKey())
                .append("title", c.getTitle())
                .append("description", c.getDescription())
                .append("avatarUrl", c.getAvatarUrl())
                .append("createdBy", c.getCreatedBy())
                .append("lastSeq", c.getLastSeq())
                .append("lastMessageId", c.getLastMessageId())
                .append("lastMessagePreview", c.getLastMessagePreview())
                .append("lastMessageSenderId", c.getLastMessageSenderId())
                .append("lastMessageAt", date(c.getLastMessageAt()))
                .append("activityAt", date(c.activityAt()))
                .append("metadata", Json.write(c.getMetadata()))
                .append("createdAt", date(c.getCreatedAt()))
                .append("updatedAt", date(c.getUpdatedAt()));
    }

    private static Conversation toConversation(Document d) {
        String type = d.getString("type");
        return Conversation.builder()
                .tenantId(d.getString("tenantId"))
                .id(d.getString("conversationId"))
                .type(type == null ? null : Conversation.Type.valueOf(type))
                .directKey(d.getString("directKey"))
                .title(d.getString("title"))
                .description(d.getString("description"))
                .avatarUrl(d.getString("avatarUrl"))
                .createdBy(d.getString("createdBy"))
                .lastSeq(longValue(d, "lastSeq"))
                .lastMessageId(d.getString("lastMessageId"))
                .lastMessagePreview(d.getString("lastMessagePreview"))
                .lastMessageSenderId(d.getString("lastMessageSenderId"))
                .lastMessageAt(instant(d, "lastMessageAt"))
                .metadata(metadata(d))
                .createdAt(instant(d, "createdAt"))
                .updatedAt(instant(d, "updatedAt"))
                .build();
    }

    private static String participantId(Participant p) {
        return key(p.getTenantId(), p.getConversationId(), p.getUserId());
    }

    private static Document participantDoc(Participant p) {
        return new Document("_id", participantId(p))
                .append("tenantId", p.getTenantId())
                .append("conversationId", p.getConversationId())
                .append("userId", p.getUserId())
                .append("role", p.getRole() == null ? Participant.Role.MEMBER.name() : p.getRole().name())
                .append("joinedSeq", p.getJoinedSeq())
                .append("deliveredSeq", p.getDeliveredSeq())
                .append("readSeq", p.getReadSeq())
                .append("muted", p.isMuted())
                .append("archived", p.isArchived())
                .append("pinned", p.isPinned())
                .append("joinedAt", date(p.getJoinedAt()))
                .append("leftAt", date(p.getLeftAt()))
                .append("updatedAt", date(p.getUpdatedAt()));
    }

    private static Participant toParticipant(Document d) {
        return Participant.builder()
                .tenantId(d.getString("tenantId"))
                .conversationId(d.getString("conversationId"))
                .userId(d.getString("userId"))
                .role(Participant.Role.valueOf(d.getString("role")))
                .joinedSeq(longValue(d, "joinedSeq"))
                .deliveredSeq(longValue(d, "deliveredSeq"))
                .readSeq(longValue(d, "readSeq"))
                .muted(bool(d, "muted"))
                .archived(bool(d, "archived"))
                .pinned(bool(d, "pinned"))
                .joinedAt(instant(d, "joinedAt"))
                .leftAt(instant(d, "leftAt"))
                .updatedAt(instant(d, "updatedAt"))
                .build();
    }

    private static Document messageDoc(ChatMessage m, long seq) {
        return new Document("_id", key(m.getTenantId(), m.getConversationId(), m.getId()))
                .append("tenantId", m.getTenantId())
                .append("conversationId", m.getConversationId())
                .append("messageId", m.getId())
                .append("seq", seq)
                .append("senderId", m.getSenderId())
                .append("clientMsgId", m.getClientMsgId())
                .append("type", m.getType() == null ? ChatMessage.Type.TEXT.name() : m.getType().name())
                .append("content", m.getContent())
                .append("attachment", attachmentDoc(m.getAttachment()))
                .append("replyToId", m.getReplyToId())
                .append("forwarded", m.isForwarded())
                .append("reactions", reactionsDoc(m.getReactions()))
                .append("edited", m.isEdited())
                .append("editedAt", date(m.getEditedAt()))
                .append("deleted", m.isDeleted())
                .append("deletedAt", date(m.getDeletedAt()))
                .append("metadata", Json.write(m.getMetadata()))
                .append("createdAt", date(m.getCreatedAt()));
    }

    private static ChatMessage toMessage(Document d) {
        return ChatMessage.builder()
                .tenantId(d.getString("tenantId"))
                .id(d.getString("messageId"))
                .conversationId(d.getString("conversationId"))
                .seq(longValue(d, "seq"))
                .senderId(d.getString("senderId"))
                .clientMsgId(d.getString("clientMsgId"))
                .type(ChatMessage.Type.valueOf(d.getString("type")))
                .content(d.getString("content"))
                .attachment(toAttachment(d.get("attachment", Document.class)))
                .replyToId(d.getString("replyToId"))
                .forwarded(bool(d, "forwarded"))
                .reactions(toReactions(d.getList("reactions", Document.class)))
                .edited(bool(d, "edited"))
                .editedAt(instant(d, "editedAt"))
                .deleted(bool(d, "deleted"))
                .deletedAt(instant(d, "deletedAt"))
                .metadata(metadata(d))
                .createdAt(instant(d, "createdAt"))
                .build();
    }

    /** Embedded document of the attachment's non-null fields (driven by Jackson so new fields persist automatically). */
    @SuppressWarnings("unchecked")
    private static Document attachmentDoc(ChatMessage.Attachment attachment) {
        return attachment == null ? null : new Document(Json.convert(attachment, Map.class));
    }

    private static ChatMessage.Attachment toAttachment(Document d) {
        return d == null ? null : Json.convert(d, ChatMessage.Attachment.class);
    }

    /** Reactions as an ordered array of {emoji, userIds}: emoji never become field names. */
    private static List<Document> reactionsDoc(Map<String, Set<String>> reactions) {
        List<Document> result = new ArrayList<>();
        if (reactions != null) {
            reactions.forEach((emoji, userIds) -> {
                if (userIds != null && !userIds.isEmpty()) {
                    result.add(new Document("emoji", emoji).append("userIds", new ArrayList<>(userIds)));
                }
            });
        }
        return result;
    }

    private static Map<String, Set<String>> toReactions(List<Document> docs) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        if (docs != null) {
            for (Document d : docs) {
                List<String> userIds = d.getList("userIds", String.class);
                result.put(d.getString("emoji"), userIds == null ? new LinkedHashSet<>() : new LinkedHashSet<>(userIds));
            }
        }
        return result;
    }
}
