package com.ayush.chat.store.firestore;

import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.core.error.InfrastructureUnavailableException;
import com.ayush.chat.core.util.Json;
import com.ayush.chat.store.MessagePreview;
import com.ayush.chat.store.MessageStore;
import com.ayush.chat.store.UserSearch;
import com.ayush.chat.store.model.AppendResult;
import com.ayush.chat.store.model.ChatMessage;
import com.ayush.chat.store.model.ChatUser;
import com.ayush.chat.store.model.Conversation;
import com.ayush.chat.store.model.InboxItem;
import com.ayush.chat.store.model.Participant;
import com.ayush.chat.store.model.StoreStats;
import com.google.api.core.ApiFuture;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreException;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.TransactionOptions;
import com.google.cloud.firestore.WriteBatch;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * {@link MessageStore} on Google Cloud Firestore (native mode). Data is laid out hierarchically
 * under one document per tenant, so tenant isolation is structural and every query is a
 * single-collection query that Firestore's automatic single-field indexes serve (no composite
 * indexes to deploy):
 * <pre>
 *   {p}tenants/{tenant}
 *     {p}users/{user}                      profile
 *       {p}blocks/{blockedUser}
 *       {p}memberships/{conversation}       inbox index (deleted when the user leaves)
 *     {p}direct/{directKey}                 -> conversationId
 *     {p}conversations/{conversation}       lastSeq, last-message fields
 *       {p}participants/{user}
 *       {p}messages/{messageId}             seq field orders the history
 *       {p}client_msg_ids/{sender|clientMsgId} -> messageId
 * </pre>
 * Seq allocation, the client-id dedupe and the sender's watermarks are one transaction, so
 * sequences are dense with no holes. All appends to a conversation contend on its document;
 * Firestore sustains roughly one write per second per document, which bounds a single very
 * busy conversation (not the tenant). Document ids are escaped so any user/conversation id is a
 * legal, collision-free document id. Metadata is stored as JSON text (tenant-defined keys).
 * <p>
 * Every call goes through {@link #await}: Firestore failures, and calls after {@link #close()},
 * surface as {@link InfrastructureUnavailableException} (503).
 */
@Slf4j
public class FirestoreMessageStore implements MessageStore {

    private static final String COMPONENT = ConnectorKind.MESSAGE_STORE.getDisplayName();

    /** Upper bound on memberships read per inbox request (the inbox is sorted client-side). */
    private static final int INBOX_SCAN_LIMIT = 5_000;
    private static final int GET_ALL_BATCH = 300;
    private static final int WRITE_BATCH = 400;
    private static final int PAGE_SIZE = 500;
    private static final TransactionOptions TX = TransactionOptions.createReadWriteOptionsBuilder()
            .setNumberOfAttempts(25).build();

    private final Firestore db;
    private final String projectId;
    private final String databaseId;
    private final String prefix;
    private final AtomicBoolean closed = new AtomicBoolean();

    public FirestoreMessageStore(FirestoreOptions options, String collectionPrefix) {
        this.projectId = options.getProjectId();
        this.databaseId = options.getDatabaseId();
        this.prefix = collectionPrefix == null ? "" : collectionPrefix;
        this.db = options.getService();
        try {
            ping();
        } catch (RuntimeException e) {
            closeQuietly();
            throw new IllegalStateException("Could not reach Firestore project '" + projectId + "' database '"
                    + databaseId + "': " + e.getMessage(), e);
        }
    }

    private void ping() {
        try {
            tenants().limit(1).get().get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("timed out after 15s", e);
        }
    }

    @Override
    public String providerId() {
        return "firestore";
    }

    @Override
    public HealthStatus health() {
        try {
            ping();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("projectId", projectId);
            details.put("databaseId", databaseId);
            details.put("collectionPrefix", prefix);
            return HealthStatus.up("Firestore reachable", details);
        } catch (RuntimeException e) {
            return HealthStatus.down("Firestore unreachable: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closeQuietly();
        }
    }

    private void closeQuietly() {
        try {
            db.close();
        } catch (Exception e) {
            log.warn("Error closing Firestore client: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ paths

    /**
     * Escapes {@code %}, {@code /} and {@code |} and joins parts with {@code |}, so the result is a
     * legal document id and distinct inputs never collide. Reserved ids ({@code .}, {@code ..},
     * {@code __x__}, empty) get a {@code %_} prefix, which escaped output can never start with.
     */
    static String docId(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = Objects.requireNonNull(parts[i], "id part");
            if (i > 0) {
                sb.append('|');
            }
            for (int j = 0; j < part.length(); j++) {
                char ch = part.charAt(j);
                switch (ch) {
                    case '%' -> sb.append("%25");
                    case '/' -> sb.append("%2F");
                    case '|' -> sb.append("%7C");
                    default -> sb.append(ch);
                }
            }
        }
        String id = sb.toString();
        boolean reserved = id.isEmpty() || id.equals(".") || id.equals("..")
                || (id.length() >= 4 && id.startsWith("__") && id.endsWith("__"));
        return reserved ? "%_" + id : id;
    }

    private CollectionReference tenants() {
        return db.collection(prefix + "tenants");
    }

    private DocumentReference tenant(String tenantId) {
        return tenants().document(docId(tenantId));
    }

    private CollectionReference users(String tenantId) {
        return tenant(tenantId).collection(prefix + "users");
    }

    private DocumentReference userRef(String tenantId, String userId) {
        return users(tenantId).document(docId(userId));
    }

    private CollectionReference blocks(String tenantId, String blockerId) {
        return userRef(tenantId, blockerId).collection(prefix + "blocks");
    }

    private DocumentReference membershipRef(String tenantId, String userId, String conversationId) {
        return userRef(tenantId, userId).collection(prefix + "memberships").document(docId(conversationId));
    }

    private CollectionReference conversations(String tenantId) {
        return tenant(tenantId).collection(prefix + "conversations");
    }

    private DocumentReference conversationRef(String tenantId, String conversationId) {
        return conversations(tenantId).document(docId(conversationId));
    }

    private DocumentReference directRef(String tenantId, String directKey) {
        return tenant(tenantId).collection(prefix + "direct").document(docId(directKey));
    }

    private CollectionReference participants(String tenantId, String conversationId) {
        return conversationRef(tenantId, conversationId).collection(prefix + "participants");
    }

    private DocumentReference participantRef(String tenantId, String conversationId, String userId) {
        return participants(tenantId, conversationId).document(docId(userId));
    }

    private CollectionReference messages(String tenantId, String conversationId) {
        return conversationRef(tenantId, conversationId).collection(prefix + "messages");
    }

    private DocumentReference clientMsgRef(String tenantId, String conversationId, String senderId, String clientMsgId) {
        return conversationRef(tenantId, conversationId).collection(prefix + "client_msg_ids")
                .document(docId(senderId, clientMsgId));
    }

    // ------------------------------------------------------------------ users

    @Override
    public ChatUser upsertUser(ChatUser user) {
        DocumentReference ref = userRef(user.getTenantId(), user.getId());
        Map<String, Object> stored = await(() -> db.runTransaction(tx -> {
            DocumentSnapshot existing = tx.get(ref).get();
            Map<String, Object> doc = userDoc(user);
            if (existing.exists()) {
                doc.put("createdAt", existing.get("createdAt"));
                if (user.getLastSeenAt() == null) {
                    doc.put("lastSeenAt", existing.get("lastSeenAt"));
                }
            }
            tx.set(ref, doc);
            return doc;
        }, TX));
        return toUser(stored);
    }

    @Override
    public Optional<ChatUser> findUser(String tenantId, String userId) {
        DocumentSnapshot snap = await(() -> userRef(tenantId, userId).get());
        return snap.exists() ? Optional.of(toUser(snap.getData())) : Optional.empty();
    }

    @Override
    public List<ChatUser> findUsers(String tenantId, Collection<String> userIds) {
        List<DocumentReference> refs = userIds.stream().distinct().map(id -> userRef(tenantId, id)).toList();
        return getAll(refs).stream().filter(DocumentSnapshot::exists).map(s -> toUser(s.getData())).toList();
    }

    /** Blank queries page on the id index; otherwise the tenant's users are streamed and filtered client-side. */
    @Override
    public List<ChatUser> searchUsers(String tenantId, String query, int offset, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String q = UserSearch.normalize(query);
        Query byId = users(tenantId).orderBy("id");
        if (q.isEmpty()) {
            return await(() -> byId.offset(Math.max(0, offset)).limit(limit).get()).getDocuments().stream()
                    .map(s -> toUser(s.getData())).toList();
        }
        List<ChatUser> result = new ArrayList<>();
        int skipped = 0;
        DocumentSnapshot last = null;
        while (true) {
            Query page = last == null ? byId.limit(PAGE_SIZE) : byId.startAfter(last).limit(PAGE_SIZE);
            List<QueryDocumentSnapshot> docs = await(() -> page.get()).getDocuments();
            for (QueryDocumentSnapshot doc : docs) {
                ChatUser u = toUser(doc.getData());
                if (UserSearch.matches(u, q)) {
                    if (skipped++ >= offset) {
                        result.add(u);
                        if (result.size() >= limit) {
                            return result;
                        }
                    }
                }
            }
            if (docs.size() < PAGE_SIZE) {
                return result;
            }
            last = docs.get(docs.size() - 1);
        }
    }

    @Override
    public long countUsers(String tenantId) {
        return await(() -> users(tenantId).count().get()).getCount();
    }

    @Override
    public void updateLastSeen(String tenantId, String userId, Instant lastSeenAt) {
        ignoreNotFound(() -> await(() -> userRef(tenantId, userId).update("lastSeenAt", ts(lastSeenAt))));
    }

    // ------------------------------------------------------------------ blocks

    @Override
    public void setBlocked(String tenantId, String blockerId, String blockedId, boolean blocked) {
        DocumentReference ref = blocks(tenantId, blockerId).document(docId(blockedId));
        if (blocked) {
            await(() -> ref.set(Map.of("blockedId", blockedId, "createdAt", ts(Instant.now()))));
        } else {
            await(() -> ref.delete());
        }
    }

    @Override
    public boolean isBlocked(String tenantId, String blockerId, String blockedId) {
        return await(() -> blocks(tenantId, blockerId).document(docId(blockedId)).get()).exists();
    }

    @Override
    public List<String> listBlocked(String tenantId, String blockerId) {
        return await(() -> blocks(tenantId, blockerId).orderBy("blockedId").get()).getDocuments().stream()
                .map(d -> d.getString("blockedId")).toList();
    }

    // ------------------------------------------------------------------ conversations

    @Override
    public Conversation createConversation(Conversation conversation, List<Participant> initial) {
        String tenantId = conversation.getTenantId();
        DocumentReference ref = conversationRef(tenantId, conversation.getId());
        Map<String, Object> doc = conversationDoc(conversation);
        if (conversation.getType() == Conversation.Type.DIRECT) {
            DocumentReference index = directRef(tenantId, conversation.getDirectKey());
            // one transaction: the direct-key index, the conversation and its members appear together
            return await(() -> db.runTransaction(tx -> {
                DocumentSnapshot existing = tx.get(index).get();
                if (existing.exists()) {
                    DocumentSnapshot winner = tx.get(conversationRef(tenantId, existing.getString("conversationId"))).get();
                    if (winner.exists()) {
                        return toConversation(winner.getData());
                    }
                }
                tx.set(index, Map.of("conversationId", conversation.getId(), "createdAt", ts(Instant.now())));
                tx.set(ref, doc);
                for (Participant p : initial) {
                    tx.set(participantRef(tenantId, p.getConversationId(), p.getUserId()), participantDoc(p));
                    if (p.isActive()) {
                        tx.set(membershipRef(tenantId, p.getUserId(), p.getConversationId()), membershipDoc(p));
                    }
                }
                return toConversation(doc);
            }, TX));
        }
        // Members first (possibly several batches for large groups), the conversation document last,
        // so whoever can see the conversation also sees its members.
        WriteBatch batch = db.batch();
        int ops = 0;
        for (Participant p : initial) {
            batch.set(participantRef(tenantId, p.getConversationId(), p.getUserId()), participantDoc(p));
            if (p.isActive()) {
                batch.set(membershipRef(tenantId, p.getUserId(), p.getConversationId()), membershipDoc(p));
                ops++;
            }
            if (++ops >= WRITE_BATCH) {
                await(batch::commit);
                batch = db.batch();
                ops = 0;
            }
        }
        if (ops > 0) {
            await(batch::commit);
        }
        try {
            await(() -> ref.create(doc));
        } catch (RuntimeException e) {
            if (!hasStatus(e, Status.Code.ALREADY_EXISTS, StatusCode.Code.ALREADY_EXISTS)) {
                throw e;
            }
            // same id created twice (caller retry): return what is stored, never reset lastSeq
            return findConversation(tenantId, conversation.getId()).orElseThrow(() -> e);
        }
        return toConversation(doc);
    }

    @Override
    public Optional<Conversation> findConversation(String tenantId, String conversationId) {
        DocumentSnapshot snap = await(() -> conversationRef(tenantId, conversationId).get());
        return snap.exists() ? Optional.of(toConversation(snap.getData())) : Optional.empty();
    }

    @Override
    public Optional<Conversation> findDirectConversation(String tenantId, String directKey) {
        DocumentSnapshot index = await(() -> directRef(tenantId, directKey).get());
        return index.exists() ? findConversation(tenantId, index.getString("conversationId")) : Optional.empty();
    }

    @Override
    public void updateConversation(Conversation conversation) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("title", conversation.getTitle());
        fields.put("description", conversation.getDescription());
        fields.put("avatarUrl", conversation.getAvatarUrl());
        fields.put("metadata", Json.write(conversation.getMetadata()));
        fields.put("updatedAt", ts(conversation.getUpdatedAt()));
        ignoreNotFound(() -> await(() -> conversationRef(conversation.getTenantId(), conversation.getId()).update(fields)));
    }

    @Override
    public List<InboxItem> listInbox(String tenantId, String userId, int limit, Instant before) {
        if (limit <= 0) {
            return List.of();
        }
        List<String> conversationIds = await(() -> userRef(tenantId, userId).collection(prefix + "memberships")
                .limit(INBOX_SCAN_LIMIT).get()).getDocuments().stream()
                .map(d -> d.getString("conversationId")).toList();
        List<DocumentSnapshot> convs = getAll(conversationIds.stream().map(id -> conversationRef(tenantId, id)).toList());
        List<DocumentSnapshot> selves = getAll(conversationIds.stream()
                .map(id -> participantRef(tenantId, id, userId)).toList());
        List<InboxItem> items = new ArrayList<>();
        for (int i = 0; i < conversationIds.size(); i++) {
            DocumentSnapshot c = convs.get(i);
            DocumentSnapshot p = selves.get(i);
            if (!c.exists() || !p.exists()) {
                continue;
            }
            Conversation conversation = toConversation(c.getData());
            Participant self = toParticipant(p.getData());
            if (self.isActive() && (before == null || conversation.activityAt().isBefore(before))) {
                items.add(new InboxItem(conversation, self));
            }
        }
        items.sort(Comparator.comparing((InboxItem i) -> i.conversation().activityAt()).reversed()
                .thenComparing(i -> i.conversation().getId()));
        return items.size() > limit ? new ArrayList<>(items.subList(0, limit)) : items;
    }

    // ------------------------------------------------------------------ participants

    @Override
    public List<Participant> listParticipants(String tenantId, String conversationId) {
        return await(() -> participants(tenantId, conversationId).get()).getDocuments().stream()
                .map(d -> toParticipant(d.getData()))
                .filter(Participant::isActive)
                .sorted(Comparator.comparing(Participant::getUserId))
                .toList();
    }

    @Override
    public Optional<Participant> findParticipant(String tenantId, String conversationId, String userId) {
        DocumentSnapshot snap = await(() -> participantRef(tenantId, conversationId, userId).get());
        return snap.exists() ? Optional.of(toParticipant(snap.getData())) : Optional.empty();
    }

    @Override
    public void upsertParticipant(Participant p) {
        DocumentReference ref = participantRef(p.getTenantId(), p.getConversationId(), p.getUserId());
        DocumentReference membership = membershipRef(p.getTenantId(), p.getUserId(), p.getConversationId());
        await(() -> db.runTransaction(tx -> {
            DocumentSnapshot existing = tx.get(ref).get();
            Map<String, Object> doc = participantDoc(p);
            if (existing.exists()) {
                // Watermarks only move forward, even when the caller started from a stale read.
                doc.put("deliveredSeq", Math.max(p.getDeliveredSeq(), longValue(existing.get("deliveredSeq"))));
                doc.put("readSeq", Math.max(p.getReadSeq(), longValue(existing.get("readSeq"))));
            }
            tx.set(ref, doc);
            if (p.isActive()) {
                tx.set(membership, membershipDoc(p));
            } else {
                tx.delete(membership);
            }
            return null;
        }, TX));
    }

    @Override
    public void removeParticipant(String tenantId, String conversationId, String userId, Instant at) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("leftAt", ts(at));
        fields.put("updatedAt", ts(at));
        WriteBatch batch = db.batch();
        batch.update(participantRef(tenantId, conversationId, userId), fields);
        batch.delete(membershipRef(tenantId, userId, conversationId));
        ignoreNotFound(() -> await(batch::commit));
    }

    @Override
    public void advanceReadState(String tenantId, String conversationId, String userId, Long deliveredSeq, Long readSeq) {
        Long delivered = deliveredSeq == null ? readSeq : readSeq == null ? deliveredSeq : Math.max(deliveredSeq, readSeq);
        if (delivered == null) {
            return;
        }
        DocumentReference ref = participantRef(tenantId, conversationId, userId);
        await(() -> db.runTransaction(tx -> {
            DocumentSnapshot snap = tx.get(ref).get();
            Map<String, Object> updates = snap.exists() ? watermarkUpdates(snap, delivered, readSeq) : Map.of();
            if (!updates.isEmpty()) {
                tx.update(ref, updates);
            }
            return null;
        }, TX));
    }

    /** Fields to write so watermarks only move forward; empty when nothing advances. */
    private static Map<String, Object> watermarkUpdates(DocumentSnapshot snap, long delivered, Long read) {
        Map<String, Object> updates = new HashMap<>();
        if (delivered > longValue(snap.get("deliveredSeq"))) {
            updates.put("deliveredSeq", delivered);
        }
        if (read != null && read > longValue(snap.get("readSeq"))) {
            updates.put("readSeq", read);
        }
        if (!updates.isEmpty()) {
            updates.put("updatedAt", ts(Instant.now()));
        }
        return updates;
    }

    // ------------------------------------------------------------------ messages

    @Override
    public AppendResult appendMessage(ChatMessage message) {
        String tenantId = message.getTenantId();
        String conversationId = message.getConversationId();
        DocumentReference convRef = conversationRef(tenantId, conversationId);
        DocumentReference senderRef = participantRef(tenantId, conversationId, message.getSenderId());
        DocumentReference msgRef = messages(tenantId, conversationId).document(docId(message.getId()));
        DocumentReference dedupeRef = message.getClientMsgId() == null ? null
                : clientMsgRef(tenantId, conversationId, message.getSenderId(), message.getClientMsgId());
        return await(() -> db.runTransaction(tx -> {
            // all reads first, as Firestore transactions require
            DocumentSnapshot conv = tx.get(convRef).get();
            if (!conv.exists()) {
                throw DomainException.notFound("Conversation");
            }
            if (dedupeRef != null) {
                DocumentSnapshot claim = tx.get(dedupeRef).get();
                if (claim.exists()) {
                    DocumentSnapshot original = tx.get(messages(tenantId, conversationId)
                            .document(docId(claim.getString("messageId")))).get();
                    if (original.exists()) {
                        return new AppendResult(toMessage(original.getData()), true);
                    }
                }
            }
            DocumentSnapshot sender = tx.get(senderRef).get();
            long seq = longValue(conv.get("lastSeq")) + 1;
            Map<String, Object> doc = messageDoc(message, seq);
            Timestamp at = ts(message.getCreatedAt());
            Map<String, Object> last = new HashMap<>();
            last.put("lastSeq", seq);
            last.put("lastMessageId", message.getId());
            last.put("lastMessagePreview", MessagePreview.of(message));
            last.put("lastMessageSenderId", message.getSenderId());
            last.put("lastMessageAt", at);
            last.put("updatedAt", at);
            tx.create(msgRef, doc);
            tx.update(convRef, last);
            if (dedupeRef != null) {
                tx.set(dedupeRef, Map.of("messageId", message.getId(), "createdAt", ts(Instant.now())));
            }
            if (sender.exists()) {
                Map<String, Object> watermarks = watermarkUpdates(sender, seq, seq);
                if (!watermarks.isEmpty()) {
                    tx.update(senderRef, watermarks);
                }
            }
            return new AppendResult(toMessage(doc), false);
        }, TX));
    }

    @Override
    public Optional<ChatMessage> findMessage(String tenantId, String conversationId, String messageId) {
        DocumentSnapshot snap = await(() -> messages(tenantId, conversationId).document(docId(messageId)).get());
        return snap.exists() ? Optional.of(toMessage(snap.getData())) : Optional.empty();
    }

    @Override
    public List<ChatMessage> listMessages(String tenantId, String conversationId, Long afterSeq, Long beforeSeq, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        CollectionReference col = messages(tenantId, conversationId);
        if (afterSeq != null) {
            return await(() -> col.whereGreaterThan("seq", afterSeq).orderBy("seq").limit(limit).get()).getDocuments()
                    .stream().map(d -> toMessage(d.getData())).toList();
        }
        Query newest = beforeSeq == null ? col : col.whereLessThan("seq", beforeSeq);
        List<ChatMessage> result = new ArrayList<>(await(() -> newest.orderBy("seq", Query.Direction.DESCENDING).limit(limit)
                .get()).getDocuments().stream().map(d -> toMessage(d.getData())).toList());
        Collections.reverse(result);
        return result;
    }

    @Override
    public void updateMessage(ChatMessage message) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("content", message.getContent());
        fields.put("attachment", attachmentMap(message.getAttachment()));
        fields.put("reactions", reactionsList(message.getReactions()));
        fields.put("edited", message.isEdited());
        fields.put("editedAt", ts(message.getEditedAt()));
        fields.put("deleted", message.isDeleted());
        fields.put("deletedAt", ts(message.getDeletedAt()));
        fields.put("metadata", Json.write(message.getMetadata()));
        DocumentReference ref = messages(message.getTenantId(), message.getConversationId())
                .document(docId(message.getId()));
        ignoreNotFound(() -> await(() -> ref.update(fields)));
    }

    // ------------------------------------------------------------------ admin

    /**
     * Users and conversations are count aggregations. Messages per tenant are the sum of the
     * conversations' {@code lastSeq} (exact, because seq allocation is transactional and dense).
     */
    @Override
    public StoreStats stats(String tenantId) {
        if (tenantId == null) {
            return new StoreStats(
                    await(() -> db.collectionGroup(prefix + "users").count().get()).getCount(),
                    await(() -> db.collectionGroup(prefix + "conversations").count().get()).getCount(),
                    await(() -> db.collectionGroup(prefix + "messages").count().get()).getCount());
        }
        long messages = 0;
        Query byId = conversations(tenantId).select("lastSeq").orderBy(FieldPath.documentId());
        DocumentSnapshot last = null;
        while (true) {
            Query page = last == null ? byId.limit(PAGE_SIZE) : byId.startAfter(last).limit(PAGE_SIZE);
            List<QueryDocumentSnapshot> docs = await(() -> page.get()).getDocuments();
            for (QueryDocumentSnapshot doc : docs) {
                messages += longValue(doc.get("lastSeq"));
            }
            if (docs.size() < PAGE_SIZE) {
                break;
            }
            last = docs.get(docs.size() - 1);
        }
        return new StoreStats(countUsers(tenantId), await(() -> conversations(tenantId).count().get()).getCount(), messages);
    }

    // ------------------------------------------------------------------ helpers

    private List<DocumentSnapshot> getAll(List<DocumentReference> refs) {
        List<DocumentSnapshot> result = new ArrayList<>(refs.size());
        for (int from = 0; from < refs.size(); from += GET_ALL_BATCH) {
            List<DocumentReference> chunk = refs.subList(from, Math.min(refs.size(), from + GET_ALL_BATCH));
            Map<String, DocumentSnapshot> byPath = new HashMap<>();
            for (DocumentSnapshot snap : await(() -> db.getAll(chunk.toArray(new DocumentReference[0])))) {
                byPath.put(snap.getReference().getPath(), snap);
            }
            chunk.forEach(ref -> result.add(byPath.get(ref.getPath())));
        }
        return result;
    }

    /**
     * Issues one Firestore call and waits for it. Outcomes our own transaction code throws (such as
     * not found) surface unchanged; every Firestore failure, and use after close, is a 503.
     */
    private <T> T await(Supplier<ApiFuture<T>> call) {
        if (closed.get()) {
            throw new InfrastructureUnavailableException(COMPONENT, "store closed");
        }
        try {
            return call.get().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InfrastructureUnavailableException(COMPONENT, e);
        } catch (ExecutionException e) {
            for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
                if (t instanceof DomainException domain) {
                    throw domain;
                }
            }
            throw new InfrastructureUnavailableException(COMPONENT, e.getCause());
        } catch (FirestoreException | ApiException | StatusRuntimeException e) {
            throw new InfrastructureUnavailableException(COMPONENT, e); // failed before a future was returned
        }
    }

    private static void ignoreNotFound(Runnable write) {
        try {
            write.run();
        } catch (RuntimeException e) {
            if (!hasStatus(e, Status.Code.NOT_FOUND, StatusCode.Code.NOT_FOUND)) {
                throw e;
            }
        }
    }

    private static boolean hasStatus(Throwable error, Status.Code grpc, StatusCode.Code gax) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof FirestoreException fe && fe.getStatus() != null && fe.getStatus().getCode() == grpc) {
                return true;
            }
            if (t instanceof ApiException api && api.getStatusCode().getCode() == gax) {
                return true;
            }
            if (t instanceof StatusRuntimeException sre && sre.getStatus().getCode() == grpc) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ mapping

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.ofTimeSecondsAndNanos(instant.getEpochSecond(), instant.getNano());
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp t) {
            return Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
        }
        return null;
    }

    private static long longValue(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static boolean bool(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static Map<String, Object> userDoc(ChatUser u) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("tenantId", u.getTenantId());
        doc.put("id", u.getId());
        doc.put("displayName", u.getDisplayName());
        doc.put("email", u.getEmail());
        doc.put("phone", u.getPhone());
        doc.put("avatarUrl", u.getAvatarUrl());
        doc.put("about", u.getAbout());
        doc.put("pushTokens", u.getPushTokens() == null ? List.of() : new ArrayList<>(u.getPushTokens()));
        doc.put("metadata", Json.write(u.getMetadata()));
        doc.put("deactivated", u.isDeactivated());
        doc.put("createdAt", ts(u.getCreatedAt()));
        doc.put("updatedAt", ts(u.getUpdatedAt()));
        doc.put("lastSeenAt", ts(u.getLastSeenAt()));
        return doc;
    }

    @SuppressWarnings("unchecked")
    private static ChatUser toUser(Map<String, Object> d) {
        List<String> tokens = (List<String>) d.get("pushTokens");
        return ChatUser.builder()
                .tenantId(str(d.get("tenantId")))
                .id(str(d.get("id")))
                .displayName(str(d.get("displayName")))
                .email(str(d.get("email")))
                .phone(str(d.get("phone")))
                .avatarUrl(str(d.get("avatarUrl")))
                .about(str(d.get("about")))
                .pushTokens(tokens == null ? new LinkedHashSet<>() : new LinkedHashSet<>(tokens))
                .metadata(Json.readMap(str(d.get("metadata"))))
                .deactivated(bool(d.get("deactivated")))
                .createdAt(instant(d.get("createdAt")))
                .updatedAt(instant(d.get("updatedAt")))
                .lastSeenAt(instant(d.get("lastSeenAt")))
                .build();
    }

    private static Map<String, Object> conversationDoc(Conversation c) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("tenantId", c.getTenantId());
        doc.put("id", c.getId());
        doc.put("type", c.getType() == null ? null : c.getType().name());
        doc.put("directKey", c.getDirectKey());
        doc.put("title", c.getTitle());
        doc.put("description", c.getDescription());
        doc.put("avatarUrl", c.getAvatarUrl());
        doc.put("createdBy", c.getCreatedBy());
        doc.put("lastSeq", c.getLastSeq());
        doc.put("lastMessageId", c.getLastMessageId());
        doc.put("lastMessagePreview", c.getLastMessagePreview());
        doc.put("lastMessageSenderId", c.getLastMessageSenderId());
        doc.put("lastMessageAt", ts(c.getLastMessageAt()));
        doc.put("metadata", Json.write(c.getMetadata()));
        doc.put("createdAt", ts(c.getCreatedAt()));
        doc.put("updatedAt", ts(c.getUpdatedAt()));
        return doc;
    }

    private static Conversation toConversation(Map<String, Object> d) {
        String type = str(d.get("type"));
        return Conversation.builder()
                .tenantId(str(d.get("tenantId")))
                .id(str(d.get("id")))
                .type(type == null ? null : Conversation.Type.valueOf(type))
                .directKey(str(d.get("directKey")))
                .title(str(d.get("title")))
                .description(str(d.get("description")))
                .avatarUrl(str(d.get("avatarUrl")))
                .createdBy(str(d.get("createdBy")))
                .lastSeq(longValue(d.get("lastSeq")))
                .lastMessageId(str(d.get("lastMessageId")))
                .lastMessagePreview(str(d.get("lastMessagePreview")))
                .lastMessageSenderId(str(d.get("lastMessageSenderId")))
                .lastMessageAt(instant(d.get("lastMessageAt")))
                .metadata(Json.readMap(str(d.get("metadata"))))
                .createdAt(instant(d.get("createdAt")))
                .updatedAt(instant(d.get("updatedAt")))
                .build();
    }

    private static Map<String, Object> participantDoc(Participant p) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("tenantId", p.getTenantId());
        doc.put("conversationId", p.getConversationId());
        doc.put("userId", p.getUserId());
        doc.put("role", p.getRole() == null ? Participant.Role.MEMBER.name() : p.getRole().name());
        doc.put("joinedSeq", p.getJoinedSeq());
        doc.put("deliveredSeq", p.getDeliveredSeq());
        doc.put("readSeq", p.getReadSeq());
        doc.put("muted", p.isMuted());
        doc.put("archived", p.isArchived());
        doc.put("pinned", p.isPinned());
        doc.put("joinedAt", ts(p.getJoinedAt()));
        doc.put("leftAt", ts(p.getLeftAt()));
        doc.put("updatedAt", ts(p.getUpdatedAt()));
        return doc;
    }

    private static Map<String, Object> membershipDoc(Participant p) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("conversationId", p.getConversationId());
        doc.put("joinedAt", ts(p.getJoinedAt()));
        return doc;
    }

    private static Participant toParticipant(Map<String, Object> d) {
        return Participant.builder()
                .tenantId(str(d.get("tenantId")))
                .conversationId(str(d.get("conversationId")))
                .userId(str(d.get("userId")))
                .role(Participant.Role.valueOf(str(d.get("role"))))
                .joinedSeq(longValue(d.get("joinedSeq")))
                .deliveredSeq(longValue(d.get("deliveredSeq")))
                .readSeq(longValue(d.get("readSeq")))
                .muted(bool(d.get("muted")))
                .archived(bool(d.get("archived")))
                .pinned(bool(d.get("pinned")))
                .joinedAt(instant(d.get("joinedAt")))
                .leftAt(instant(d.get("leftAt")))
                .updatedAt(instant(d.get("updatedAt")))
                .build();
    }

    private static Map<String, Object> messageDoc(ChatMessage m, long seq) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("tenantId", m.getTenantId());
        doc.put("conversationId", m.getConversationId());
        doc.put("id", m.getId());
        doc.put("seq", seq);
        doc.put("senderId", m.getSenderId());
        doc.put("clientMsgId", m.getClientMsgId());
        doc.put("type", m.getType() == null ? ChatMessage.Type.TEXT.name() : m.getType().name());
        doc.put("content", m.getContent());
        doc.put("attachment", attachmentMap(m.getAttachment()));
        doc.put("replyToId", m.getReplyToId());
        doc.put("forwarded", m.isForwarded());
        doc.put("reactions", reactionsList(m.getReactions()));
        doc.put("edited", m.isEdited());
        doc.put("editedAt", ts(m.getEditedAt()));
        doc.put("deleted", m.isDeleted());
        doc.put("deletedAt", ts(m.getDeletedAt()));
        doc.put("metadata", Json.write(m.getMetadata()));
        doc.put("createdAt", ts(m.getCreatedAt()));
        return doc;
    }

    @SuppressWarnings("unchecked")
    private static ChatMessage toMessage(Map<String, Object> d) {
        Object attachment = d.get("attachment");
        return ChatMessage.builder()
                .tenantId(str(d.get("tenantId")))
                .id(str(d.get("id")))
                .conversationId(str(d.get("conversationId")))
                .seq(longValue(d.get("seq")))
                .senderId(str(d.get("senderId")))
                .clientMsgId(str(d.get("clientMsgId")))
                .type(ChatMessage.Type.valueOf(str(d.get("type"))))
                .content(str(d.get("content")))
                .attachment(attachment == null ? null : Json.convert(attachment, ChatMessage.Attachment.class))
                .replyToId(str(d.get("replyToId")))
                .forwarded(bool(d.get("forwarded")))
                .reactions(toReactions((List<Map<String, Object>>) d.get("reactions")))
                .edited(bool(d.get("edited")))
                .editedAt(instant(d.get("editedAt")))
                .deleted(bool(d.get("deleted")))
                .deletedAt(instant(d.get("deletedAt")))
                .metadata(Json.readMap(str(d.get("metadata"))))
                .createdAt(instant(d.get("createdAt")))
                .build();
    }

    /** Non-null attachment fields as a map (driven by Jackson so new fields persist automatically). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> attachmentMap(ChatMessage.Attachment attachment) {
        return attachment == null ? null : Json.convert(attachment, Map.class);
    }

    /** Reactions as an ordered list of {emoji, userIds}: emoji never become field paths. */
    private static List<Map<String, Object>> reactionsList(Map<String, Set<String>> reactions) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (reactions != null) {
            reactions.forEach((emoji, userIds) -> {
                if (userIds != null && !userIds.isEmpty()) {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("emoji", emoji);
                    entry.put("userIds", new ArrayList<>(userIds));
                    result.add(entry);
                }
            });
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> toReactions(List<Map<String, Object>> list) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        if (list != null) {
            for (Map<String, Object> entry : list) {
                List<String> userIds = (List<String>) entry.get("userIds");
                result.put(str(entry.get("emoji")), userIds == null ? new LinkedHashSet<>() : new LinkedHashSet<>(userIds));
            }
        }
        return result;
    }
}
