package com.ayush.chat.store;

import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.core.error.ErrorCode;
import com.ayush.chat.core.util.Ids;
import com.ayush.chat.store.model.AppendResult;
import com.ayush.chat.store.model.ChatMessage;
import com.ayush.chat.store.model.ChatUser;
import com.ayush.chat.store.model.Conversation;
import com.ayush.chat.store.model.InboxItem;
import com.ayush.chat.store.model.Participant;
import com.ayush.chat.store.model.StoreStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavioural contract for every {@link MessageStore}. Each provider gets a subclass that
 * supplies a fresh store; every test uses unique tenant ids so stores may be shared.
 */
public abstract class MessageStoreConformanceTest {

    protected MessageStore store;
    protected String tenant;

    protected abstract MessageStore createStore() throws Exception;

    @BeforeEach
    void setUpStore() throws Exception {
        store = createStore();
        tenant = "t-" + Ids.randomToken(8).toLowerCase(Locale.ROOT);
    }

    @AfterEach
    void tearDownStore() {
        if (store != null) {
            store.close();
        }
    }

    protected static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    protected ChatUser user(String id, String name) {
        return store.upsertUser(ChatUser.builder().tenantId(tenant).id(id).displayName(name)
                .email(id + "@example.com").createdAt(now()).updatedAt(now()).build());
    }

    protected Conversation direct(String a, String b) {
        Instant at = now();
        Conversation c = Conversation.builder().tenantId(tenant).id(Ids.newId()).type(Conversation.Type.DIRECT)
                .directKey(Conversation.directKey(a, b)).createdBy(a).createdAt(at).updatedAt(at).build();
        return store.createConversation(c, List.of(member(c.getId(), a, Participant.Role.MEMBER, at),
                member(c.getId(), b, Participant.Role.MEMBER, at)));
    }

    protected Conversation group(String owner, String... members) {
        Instant at = now();
        Conversation c = Conversation.builder().tenantId(tenant).id(Ids.newId()).type(Conversation.Type.GROUP)
                .title("Group").createdBy(owner).createdAt(at).updatedAt(at).build();
        List<Participant> ps = new ArrayList<>();
        ps.add(member(c.getId(), owner, Participant.Role.OWNER, at));
        for (String m : members) {
            ps.add(member(c.getId(), m, Participant.Role.MEMBER, at));
        }
        return store.createConversation(c, ps);
    }

    protected Participant member(String conversationId, String userId, Participant.Role role, Instant at) {
        return Participant.builder().tenantId(tenant).conversationId(conversationId).userId(userId).role(role)
                .joinedAt(at).updatedAt(at).build();
    }

    protected AppendResult send(Conversation c, String sender, String text, String clientMsgId) {
        return store.appendMessage(ChatMessage.builder().tenantId(tenant).id(Ids.newId())
                .conversationId(c.getId()).senderId(sender).clientMsgId(clientMsgId)
                .type(ChatMessage.Type.TEXT).content(text).createdAt(now()).build());
    }

    // ------------------------------------------------------------------ users

    @Test
    void upsertAndFindUser() {
        user("alice", "Alice");
        ChatUser found = store.findUser(tenant, "alice").orElseThrow();
        assertThat(found.getDisplayName()).isEqualTo("Alice");
        assertThat(found.getEmail()).isEqualTo("alice@example.com");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(store.findUser(tenant, "nobody")).isEmpty();
    }

    @Test
    void upsertUpdatesProfileAndPreservesCreatedAt() {
        ChatUser first = user("alice", "Alice");
        Instant created = store.findUser(tenant, "alice").orElseThrow().getCreatedAt();
        store.upsertUser(first.toBuilder().displayName("Alice B").avatarUrl("https://x/a.png")
                .pushTokens(new LinkedHashSet<>(Set.of("tok-1")))
                .metadata(Map.of("plan", "gold")).createdAt(now().plusSeconds(60)).updatedAt(now()).build());
        ChatUser updated = store.findUser(tenant, "alice").orElseThrow();
        assertThat(updated.getDisplayName()).isEqualTo("Alice B");
        assertThat(updated.getAvatarUrl()).isEqualTo("https://x/a.png");
        assertThat(updated.getPushTokens()).containsExactly("tok-1");
        assertThat(updated.getMetadata()).containsEntry("plan", "gold");
        assertThat(updated.getCreatedAt()).isEqualTo(created);
    }

    @Test
    void usersAreTenantScoped() {
        user("alice", "Alice");
        String otherTenant = tenant + "-other";
        assertThat(store.findUser(otherTenant, "alice")).isEmpty();
        assertThat(store.searchUsers(otherTenant, null, 0, 10)).isEmpty();
        assertThat(store.countUsers(otherTenant)).isZero();
    }

    @Test
    void searchAndCountUsers() {
        user("alice", "Alice Smith");
        user("bob", "Bob Jones");
        user("carol", "Carol Smith");
        assertThat(store.countUsers(tenant)).isEqualTo(3);
        assertThat(store.searchUsers(tenant, "smith", 0, 10)).extracting(ChatUser::getId)
                .containsExactly("alice", "carol");
        assertThat(store.searchUsers(tenant, "BOB", 0, 10)).extracting(ChatUser::getId).containsExactly("bob");
        assertThat(store.searchUsers(tenant, null, 1, 1)).extracting(ChatUser::getId).containsExactly("bob");
        assertThat(store.findUsers(tenant, List.of("carol", "alice", "ghost"))).extracting(ChatUser::getId)
                .containsExactlyInAnyOrder("alice", "carol");
    }

    @Test
    void concurrentFirstUpsertsOfOneUserConverge() throws Exception {
        List<ChatUser> results = race(8, i -> store.upsertUser(ChatUser.builder().tenantId(tenant).id("racer")
                .displayName("Racer " + i).createdAt(now()).updatedAt(now()).build()));
        assertThat(results).extracting(ChatUser::getId).containsOnly("racer");
        assertThat(store.countUsers(tenant)).isEqualTo(1);
        assertThat(store.findUser(tenant, "racer").orElseThrow().getDisplayName()).startsWith("Racer ");
    }

    @Test
    void lastSeenIsUpdated() {
        user("alice", "Alice");
        Instant seen = now();
        store.updateLastSeen(tenant, "alice", seen);
        assertThat(store.findUser(tenant, "alice").orElseThrow().getLastSeenAt()).isEqualTo(seen);
    }

    // ------------------------------------------------------------------ blocks

    @Test
    void blockAndUnblock() {
        store.setBlocked(tenant, "alice", "bob", true);
        store.setBlocked(tenant, "alice", "carol", true);
        assertThat(store.isBlocked(tenant, "alice", "bob")).isTrue();
        assertThat(store.isBlocked(tenant, "bob", "alice")).isFalse();
        assertThat(store.listBlocked(tenant, "alice")).containsExactlyInAnyOrder("bob", "carol");
        store.setBlocked(tenant, "alice", "bob", false);
        store.setBlocked(tenant, "alice", "bob", false);
        assertThat(store.isBlocked(tenant, "alice", "bob")).isFalse();
        assertThat(store.listBlocked(tenant, "alice")).containsExactly("carol");
    }

    // ------------------------------------------------------------------ conversations

    @Test
    void directConversationIsIdempotent() {
        Conversation first = direct("alice", "bob");
        Conversation second = direct("bob", "alice");
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(store.findDirectConversation(tenant, Conversation.directKey("alice", "bob")))
                .get().extracting(Conversation::getId).isEqualTo(first.getId());
        assertThat(store.listParticipants(tenant, first.getId())).extracting(Participant::getUserId)
                .containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void concurrentDirectCreationConvergesOnOneConversation() throws Exception {
        List<Conversation> created = race(8, i -> i % 2 == 0 ? direct("alice", "bob") : direct("bob", "alice"));
        String id = created.get(0).getId();
        assertThat(created).extracting(Conversation::getId).containsOnly(id);
        assertThat(store.stats(tenant).conversations()).isEqualTo(1);
        assertThat(store.listParticipants(tenant, id)).extracting(Participant::getUserId)
                .containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void groupConversationAndParticipants() {
        Conversation g = group("alice", "bob", "carol");
        Conversation found = store.findConversation(tenant, g.getId()).orElseThrow();
        assertThat(found.getType()).isEqualTo(Conversation.Type.GROUP);
        assertThat(found.getTitle()).isEqualTo("Group");
        assertThat(found.getLastSeq()).isZero();
        assertThat(store.findParticipant(tenant, g.getId(), "alice").orElseThrow().getRole())
                .isEqualTo(Participant.Role.OWNER);

        store.removeParticipant(tenant, g.getId(), "carol", now());
        assertThat(store.listParticipants(tenant, g.getId())).extracting(Participant::getUserId)
                .containsExactlyInAnyOrder("alice", "bob");
        Participant carol = store.findParticipant(tenant, g.getId(), "carol").orElseThrow();
        assertThat(carol.getLeftAt()).isNotNull();

        store.upsertParticipant(carol.toBuilder().leftAt(null).role(Participant.Role.ADMIN).joinedSeq(5).build());
        Participant rejoined = store.findParticipant(tenant, g.getId(), "carol").orElseThrow();
        assertThat(rejoined.isActive()).isTrue();
        assertThat(rejoined.getRole()).isEqualTo(Participant.Role.ADMIN);
        assertThat(rejoined.getJoinedSeq()).isEqualTo(5);
    }

    @Test
    void updateConversationDetails() {
        Conversation g = group("alice", "bob");
        Instant at = now();
        store.updateConversation(g.toBuilder().title("Renamed").description("desc").avatarUrl("https://x/g.png")
                .metadata(Map.of("color", "blue")).updatedAt(at).build());
        Conversation found = store.findConversation(tenant, g.getId()).orElseThrow();
        assertThat(found.getTitle()).isEqualTo("Renamed");
        assertThat(found.getDescription()).isEqualTo("desc");
        assertThat(found.getAvatarUrl()).isEqualTo("https://x/g.png");
        assertThat(found.getMetadata()).containsEntry("color", "blue");
    }

    @Test
    void participantSettingsPersist() {
        Conversation c = direct("alice", "bob");
        Participant alice = store.findParticipant(tenant, c.getId(), "alice").orElseThrow();
        store.upsertParticipant(alice.toBuilder().muted(true).pinned(true).archived(true).build());
        Participant updated = store.findParticipant(tenant, c.getId(), "alice").orElseThrow();
        assertThat(updated.isMuted()).isTrue();
        assertThat(updated.isPinned()).isTrue();
        assertThat(updated.isArchived()).isTrue();
    }

    // ------------------------------------------------------------------ messages

    @Test
    void appendAssignsSequentialSeqAndUpdatesConversation() {
        Conversation c = direct("alice", "bob");
        AppendResult m1 = send(c, "alice", "hi", "c1");
        AppendResult m2 = send(c, "bob", "hello", "c2");
        assertThat(m1.duplicate()).isFalse();
        assertThat(m1.message().getSeq()).isEqualTo(1);
        assertThat(m2.message().getSeq()).isEqualTo(2);

        Conversation found = store.findConversation(tenant, c.getId()).orElseThrow();
        assertThat(found.getLastSeq()).isEqualTo(2);
        assertThat(found.getLastMessageId()).isEqualTo(m2.message().getId());
        assertThat(found.getLastMessagePreview()).isEqualTo("hello");
        assertThat(found.getLastMessageSenderId()).isEqualTo("bob");
        assertThat(found.getLastMessageAt()).isNotNull();
    }

    @Test
    void appendIsIdempotentOnClientMsgId() {
        Conversation c = direct("alice", "bob");
        AppendResult first = send(c, "alice", "hi", "same-id");
        AppendResult retry = send(c, "alice", "hi (retry)", "same-id");
        assertThat(retry.duplicate()).isTrue();
        assertThat(retry.message().getId()).isEqualTo(first.message().getId());
        assertThat(retry.message().getContent()).isEqualTo("hi");
        // a different sender may reuse the same client id
        AppendResult other = send(c, "bob", "yo", "same-id");
        assertThat(other.duplicate()).isFalse();
        assertThat(store.listMessages(tenant, c.getId(), null, null, 50)).hasSize(2);
    }

    @Test
    void concurrentRetriesOfOneSendStoreOneMessage() throws Exception {
        Conversation c = direct("alice", "bob");
        List<AppendResult> results = race(8, i -> send(c, "alice", "hi", "retry-me"));
        assertThat(results).filteredOn(r -> !r.duplicate()).hasSize(1);
        assertThat(results).extracting(r -> r.message().getId()).containsOnly(results.get(0).message().getId());
        assertThat(store.listMessages(tenant, c.getId(), null, null, 50)).hasSize(1);
        assertThat(store.findConversation(tenant, c.getId()).orElseThrow().getLastSeq()).isEqualTo(1);
    }

    @Test
    void appendToMissingConversationIsNotFound() {
        ChatMessage orphan = ChatMessage.builder().tenantId(tenant).id(Ids.newId()).conversationId("missing")
                .senderId("alice").clientMsgId("c1").content("x").createdAt(now()).build();
        assertThatThrownBy(() -> store.appendMessage(orphan)).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> store.appendMessage(orphan.toBuilder().clientMsgId(null).build()))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void appendAdvancesSenderWatermarks() {
        Conversation c = direct("alice", "bob");
        send(c, "alice", "one", null);
        send(c, "alice", "two", null);
        Participant alice = store.findParticipant(tenant, c.getId(), "alice").orElseThrow();
        Participant bob = store.findParticipant(tenant, c.getId(), "bob").orElseThrow();
        assertThat(alice.getReadSeq()).isEqualTo(2);
        assertThat(alice.getDeliveredSeq()).isEqualTo(2);
        assertThat(bob.getReadSeq()).isZero();
    }

    @Test
    void concurrentAppendsGetUniqueContiguousSeqs() throws Exception {
        Conversation c = group("alice", "bob", "carol");
        int threads = 8;
        int perThread = 15;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Long>>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            String sender = List.of("alice", "bob", "carol").get(t % 3);
            futures.add(pool.submit(() -> {
                start.await();
                List<Long> seqs = new ArrayList<>();
                for (int i = 0; i < perThread; i++) {
                    seqs.add(send(c, sender, "m", null).message().getSeq());
                }
                return seqs;
            }));
        }
        start.countDown();
        Set<Long> all = new HashSet<>();
        for (Future<List<Long>> f : futures) {
            all.addAll(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        int total = threads * perThread;
        assertThat(all).hasSize(total);
        assertThat(Collections.min(all)).isEqualTo(1L);
        assertThat(Collections.max(all)).isEqualTo((long) total);
        assertThat(store.findConversation(tenant, c.getId()).orElseThrow().getLastSeq()).isEqualTo(total);
    }

    @Test
    void listMessagesPagingAndSync() {
        Conversation c = direct("alice", "bob");
        for (int i = 1; i <= 10; i++) {
            send(c, i % 2 == 0 ? "bob" : "alice", "m" + i, "c" + i);
        }
        assertThat(store.listMessages(tenant, c.getId(), null, null, 3)).extracting(ChatMessage::getSeq)
                .containsExactly(8L, 9L, 10L);
        assertThat(store.listMessages(tenant, c.getId(), null, 8L, 3)).extracting(ChatMessage::getSeq)
                .containsExactly(5L, 6L, 7L);
        assertThat(store.listMessages(tenant, c.getId(), 7L, null, 100)).extracting(ChatMessage::getSeq)
                .containsExactly(8L, 9L, 10L);
        assertThat(store.listMessages(tenant, c.getId(), 0L, null, 2)).extracting(ChatMessage::getSeq)
                .containsExactly(1L, 2L);
        assertThat(store.listMessages(tenant, c.getId(), 10L, null, 5)).isEmpty();
    }

    @Test
    void messageFieldsRoundTrip() {
        Conversation c = direct("alice", "bob");
        Instant at = now();
        ChatMessage original = ChatMessage.builder().tenantId(tenant).id(Ids.newId()).conversationId(c.getId())
                .senderId("alice").clientMsgId("img-1").type(ChatMessage.Type.IMAGE).content("caption")
                .attachment(ChatMessage.Attachment.builder().mediaId("m1").url("https://cdn/x.jpg")
                        .mimeType("image/jpeg").size(1234L).fileName("x.jpg").width(640).height(480).build())
                .replyToId("parent").forwarded(true).metadata(Map.of("k", "v")).createdAt(at).build();
        AppendResult saved = store.appendMessage(original);
        ChatMessage found = store.findMessage(tenant, c.getId(), saved.message().getId()).orElseThrow();
        assertThat(found.getType()).isEqualTo(ChatMessage.Type.IMAGE);
        assertThat(found.getContent()).isEqualTo("caption");
        assertThat(found.getAttachment().getUrl()).isEqualTo("https://cdn/x.jpg");
        assertThat(found.getAttachment().getSize()).isEqualTo(1234L);
        assertThat(found.getAttachment().getWidth()).isEqualTo(640);
        assertThat(found.getReplyToId()).isEqualTo("parent");
        assertThat(found.isForwarded()).isTrue();
        assertThat(found.getMetadata()).containsEntry("k", "v");
        assertThat(found.getCreatedAt()).isEqualTo(at);
        assertThat(found.getSenderId()).isEqualTo("alice");
        assertThat(found.getClientMsgId()).isEqualTo("img-1");
        assertThat(store.findMessage(tenant, c.getId(), "missing")).isEmpty();
    }

    @Test
    void updateMessageEditsDeletesAndReactions() {
        Conversation c = direct("alice", "bob");
        ChatMessage m = send(c, "alice", "original", "x").message();
        Instant at = now();
        m.setContent("edited");
        m.setEdited(true);
        m.setEditedAt(at);
        m.addReaction("👍", "bob");
        m.addReaction("❤️", "alice");
        store.updateMessage(m);
        ChatMessage found = store.findMessage(tenant, c.getId(), m.getId()).orElseThrow();
        assertThat(found.getContent()).isEqualTo("edited");
        assertThat(found.isEdited()).isTrue();
        assertThat(found.getEditedAt()).isEqualTo(at);
        assertThat(found.getReactions()).containsOnlyKeys("👍", "❤️");
        assertThat(found.getReactions().get("👍")).containsExactly("bob");

        found.setDeleted(true);
        found.setDeletedAt(at);
        found.setContent(null);
        store.updateMessage(found);
        ChatMessage deleted = store.findMessage(tenant, c.getId(), m.getId()).orElseThrow();
        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.getContent()).isNull();
    }

    // ------------------------------------------------------------------ receipts & inbox

    @Test
    void readStateOnlyMovesForward() {
        Conversation c = direct("alice", "bob");
        for (int i = 0; i < 5; i++) {
            send(c, "alice", "m" + i, null);
        }
        store.advanceReadState(tenant, c.getId(), "bob", 3L, null);
        store.advanceReadState(tenant, c.getId(), "bob", null, 2L);
        Participant bob = store.findParticipant(tenant, c.getId(), "bob").orElseThrow();
        assertThat(bob.getDeliveredSeq()).isEqualTo(3);
        assertThat(bob.getReadSeq()).isEqualTo(2);
        store.advanceReadState(tenant, c.getId(), "bob", 1L, 1L);
        bob = store.findParticipant(tenant, c.getId(), "bob").orElseThrow();
        assertThat(bob.getDeliveredSeq()).isEqualTo(3);
        assertThat(bob.getReadSeq()).isEqualTo(2);
        store.advanceReadState(tenant, c.getId(), "bob", null, 5L);
        bob = store.findParticipant(tenant, c.getId(), "bob").orElseThrow();
        assertThat(bob.getReadSeq()).isEqualTo(5);
        assertThat(bob.getDeliveredSeq()).isEqualTo(5);
    }

    @Test
    void upsertParticipantNeverLowersWatermarks() {
        Conversation c = direct("alice", "bob");
        for (int i = 0; i < 4; i++) {
            send(c, "alice", "m" + i, null);
        }
        Participant stale = store.findParticipant(tenant, c.getId(), "bob").orElseThrow();
        store.advanceReadState(tenant, c.getId(), "bob", 4L, 3L);
        store.upsertParticipant(stale.toBuilder().muted(true).build());
        Participant bob = store.findParticipant(tenant, c.getId(), "bob").orElseThrow();
        assertThat(bob.isMuted()).isTrue();
        assertThat(bob.getDeliveredSeq()).isEqualTo(4);
        assertThat(bob.getReadSeq()).isEqualTo(3);
    }

    @Test
    void inboxOrderingUnreadAndPaging() throws Exception {
        Conversation first = direct("alice", "bob");
        Conversation second = direct("alice", "carol");
        Conversation third = group("dave", "alice");
        send(first, "bob", "1", null);
        Thread.sleep(5);
        send(third, "dave", "2", null);
        Thread.sleep(5);
        send(second, "carol", "3", null);
        send(second, "carol", "4", null);

        List<InboxItem> inbox = store.listInbox(tenant, "alice", 10, null);
        assertThat(inbox).extracting(i -> i.conversation().getId())
                .containsExactly(second.getId(), third.getId(), first.getId());
        assertThat(inbox.get(0).unreadCount()).isEqualTo(2);
        assertThat(inbox.get(0).self().getUserId()).isEqualTo("alice");

        List<InboxItem> page2 = store.listInbox(tenant, "alice", 10, inbox.get(0).conversation().activityAt());
        assertThat(page2).extracting(i -> i.conversation().getId()).containsExactly(third.getId(), first.getId());
        assertThat(store.listInbox(tenant, "alice", 1, null)).hasSize(1);

        store.removeParticipant(tenant, third.getId(), "alice", now());
        assertThat(store.listInbox(tenant, "alice", 10, null)).extracting(i -> i.conversation().getId())
                .doesNotContain(third.getId());
        assertThat(store.listInbox(tenant + "-other", "alice", 10, null)).isEmpty();
    }

    @Test
    void statsCountPerTenant() {
        user("alice", "Alice");
        user("bob", "Bob");
        Conversation c = direct("alice", "bob");
        send(c, "alice", "a", null);
        send(c, "bob", "b", null);
        StoreStats stats = store.stats(tenant);
        assertThat(stats.users()).isEqualTo(2);
        assertThat(stats.conversations()).isEqualTo(1);
        assertThat(stats.messages()).isEqualTo(2);
        StoreStats global = store.stats(null);
        assertThat(global.messages()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void healthIsReported() {
        assertThat(store.health().healthy()).isTrue();
    }

    @FunctionalInterface
    protected interface Attempt<T> {
        T run(int index) throws Exception;
    }

    /** Runs {@code threads} attempts released at the same instant; returns their results in index order. */
    protected static <T> List<T> race(int threads, Attempt<T> attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int index = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    return attempt.run(index);
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
