package com.ayush.chat.store;

import com.ayush.chat.core.connector.Connector;
import com.ayush.chat.store.model.AppendResult;
import com.ayush.chat.store.model.ChatMessage;
import com.ayush.chat.store.model.ChatUser;
import com.ayush.chat.store.model.Conversation;
import com.ayush.chat.store.model.InboxItem;
import com.ayush.chat.store.model.Participant;
import com.ayush.chat.store.model.StoreStats;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Data-plane storage SPI. One implementation per database family; the platform routes each
 * tenant to a store (shared, rows tagged with {@code tenantId}, or a tenant-dedicated one).
 *
 * <h2>Contract every implementation must honour</h2>
 * (enforced by {@code MessageStoreConformanceTest})
 * <ul>
 *   <li>Every read and write is scoped by {@code tenantId}; data never leaks across tenants.</li>
 *   <li>{@link #appendMessage} allocates {@code seq = conversation.lastSeq + 1} atomically under
 *       concurrency (no duplicates, no reordering), updates the conversation's last-message
 *       fields, and advances the sender's delivered/read watermarks to the new seq.</li>
 *   <li>{@link #appendMessage} is idempotent on (tenant, conversation, sender, clientMsgId) when
 *       clientMsgId is non-null: a retry returns the original message with {@code duplicate=true}.</li>
 *   <li>{@link #createConversation} for a DIRECT conversation is idempotent on directKey: if one
 *       exists it is returned unchanged.</li>
 *   <li>{@link #advanceReadState} only ever moves watermarks forward.</li>
 *   <li>Timestamps are persisted with at least millisecond precision; ids are opaque strings.</li>
 * </ul>
 */
public interface MessageStore extends Connector {

    // ---------------------------------------------------------------- users

    /** Insert or update a user (createdAt is preserved on update). Returns the stored user. */
    ChatUser upsertUser(ChatUser user);

    Optional<ChatUser> findUser(String tenantId, String userId);

    List<ChatUser> findUsers(String tenantId, Collection<String> userIds);

    /**
     * Case-insensitive substring match on id, displayName and email; a null/blank query lists
     * all users. Ordered by id.
     */
    List<ChatUser> searchUsers(String tenantId, String query, int offset, int limit);

    long countUsers(String tenantId);

    void updateLastSeen(String tenantId, String userId, Instant lastSeenAt);

    // ---------------------------------------------------------------- blocks

    void setBlocked(String tenantId, String blockerId, String blockedId, boolean blocked);

    boolean isBlocked(String tenantId, String blockerId, String blockedId);

    List<String> listBlocked(String tenantId, String blockerId);

    // ---------------------------------------------------------------- conversations

    /**
     * Create a conversation with its initial participants. Ids and timestamps must already be
     * set by the caller. DIRECT conversations are idempotent on {@code directKey}.
     */
    Conversation createConversation(Conversation conversation, List<Participant> participants);

    Optional<Conversation> findConversation(String tenantId, String conversationId);

    Optional<Conversation> findDirectConversation(String tenantId, String directKey);

    /** Update title, description, avatarUrl, metadata and updatedAt. */
    void updateConversation(Conversation conversation);

    /**
     * Conversations the user is an active participant of, newest activity first
     * (lastMessageAt, falling back to createdAt). {@code before} is an exclusive cursor on
     * that activity timestamp; null means from the newest.
     */
    List<InboxItem> listInbox(String tenantId, String userId, int limit, Instant before);

    // ---------------------------------------------------------------- participants

    /** Active participants only. */
    List<Participant> listParticipants(String tenantId, String conversationId);

    /** Returns the participant record whether active or not. */
    Optional<Participant> findParticipant(String tenantId, String conversationId, String userId);

    /**
     * Insert or replace a participant record (role, settings, re-join with leftAt=null).
     * Watermarks (deliveredSeq/readSeq) are merged with max(): an upsert built from a stale read
     * must never move a receipt backwards.
     */
    void upsertParticipant(Participant participant);

    /** Mark a participant as having left (sets leftAt). */
    void removeParticipant(String tenantId, String conversationId, String userId, Instant at);

    /** Move watermarks forward; null arguments are ignored; never decreases a value. */
    void advanceReadState(String tenantId, String conversationId, String userId, Long deliveredSeq, Long readSeq);

    // ---------------------------------------------------------------- messages

    /** See the class contract. The message's id and createdAt are set by the caller. */
    AppendResult appendMessage(ChatMessage message);

    Optional<ChatMessage> findMessage(String tenantId, String conversationId, String messageId);

    /**
     * Messages in ascending seq order.
     * <ul>
     *   <li>afterSeq set: the first {@code limit} messages with seq &gt; afterSeq (forward sync).</li>
     *   <li>otherwise: the last {@code limit} messages with seq &lt; beforeSeq (or the newest when
     *       beforeSeq is null), still returned ascending (history paging).</li>
     * </ul>
     */
    List<ChatMessage> listMessages(String tenantId, String conversationId, Long afterSeq, Long beforeSeq, int limit);

    /** Persist mutable fields: content, attachment, reactions, edited/editedAt, deleted/deletedAt, metadata. */
    void updateMessage(ChatMessage message);

    // ---------------------------------------------------------------- admin

    /** Counts for one tenant, or across all tenants when tenantId is null. */
    StoreStats stats(String tenantId);
}
