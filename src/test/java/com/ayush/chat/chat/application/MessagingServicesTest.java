package com.ayush.chat.chat.application;

import com.ayush.chat.chat.Views.ConversationView;
import com.ayush.chat.chat.Views.MessageView;
import com.ayush.chat.chat.event.ChatEvent;
import com.ayush.chat.chat.event.ControlType;
import com.ayush.chat.chat.event.DomainEvent;
import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.core.error.ErrorCode;
import com.ayush.chat.store.model.ChatMessage;
import com.ayush.chat.store.model.Participant;
import com.ayush.chat.tenant.Feature;
import com.ayush.chat.tenant.TenantLimits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.ayush.chat.chat.application.ChatFixture.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** Domain rules of the messaging services, without Spring, a database or a broker. */
class MessagingServicesTest {

    private ChatFixture f;

    @BeforeEach
    void setUp() {
        f = new ChatFixture();
        f.user("alice");
        f.user("bob");
        f.user("carol");
    }

    private MessageService.SendInput text(String clientMsgId, String content) {
        return new MessageService.SendInput(clientMsgId, ChatMessage.Type.TEXT, content, null, null, null, null);
    }

    private static void assertError(Throwable e, ErrorCode code) {
        assertThat(e).isInstanceOf(DomainException.class);
        assertThat(((DomainException) e).getErrorCode()).isEqualTo(code);
    }

    @Test
    void sendAssignsDenseSeqsFansOutAndIsIdempotent() {
        String id = f.conversations.openDirect(TENANT, "alice", "bob").id();
        f.published.clear();

        MessageService.SendResult first = f.messages.send(TENANT, "alice", id, text("c-1", "hi"));
        MessageService.SendResult second = f.messages.send(TENANT, "bob", id, text("c-2", "hey"));
        MessageService.SendResult retry = f.messages.send(TENANT, "alice", id, text("c-1", "hi"));

        assertThat(first.message().seq()).isEqualTo(1);
        assertThat(second.message().seq()).isEqualTo(2);
        assertThat(retry.duplicate()).isTrue();
        assertThat(retry.message().id()).isEqualTo(first.message().id());
        assertThat(f.published.ofType(ChatEvent.MESSAGE_NEW)).hasSize(2)
                .allSatisfy(d -> assertThat(d.users()).containsExactlyInAnyOrder("alice", "bob"));
        assertThat(f.published.domainEvents).extracting(DomainEvent::type).containsOnly(DomainEvent.MESSAGE_CREATED);
        assertThat(f.published.domainEvents.get(0).recipients()).containsExactly("bob");
    }

    @Test
    void outsidersCannotSeeOrPostAndConversationsCannotBeProbed() {
        String id = f.conversations.openDirect(TENANT, "alice", "bob").id();
        assertError(catchThrowable(() -> f.messages.send(TENANT, "carol", id, text(null, "x"))), ErrorCode.NOT_FOUND);
        assertError(catchThrowable(() -> f.conversations.get(TENANT, "carol", id)), ErrorCode.NOT_FOUND);
        assertError(catchThrowable(() -> f.messages.list(TENANT, "carol", id, null, null, 10)), ErrorCode.NOT_FOUND);
        assertError(catchThrowable(() -> f.receipts.typing(TENANT, "carol", id, true)), ErrorCode.NOT_FOUND);
    }

    @Test
    void blocksStopDirectMessagesBothWays() {
        String id = f.conversations.openDirect(TENANT, "alice", "bob").id();
        f.blocks.setBlocked(TENANT, "bob", "alice", true);

        assertThatThrownBy(() -> f.messages.send(TENANT, "alice", id, text(null, "x")))
                .hasMessageContaining("can't send");
        assertThatThrownBy(() -> f.messages.send(TENANT, "bob", id, text(null, "x")))
                .hasMessageContaining("Unblock");
        assertThat(f.published.control).anySatisfy(c -> assertThat(c.type()).isEqualTo(ControlType.BLOCK_CHANGED));

        f.blocks.setBlocked(TENANT, "bob", "alice", false);
        assertThat(f.messages.send(TENANT, "alice", id, text(null, "x")).message().seq()).isEqualTo(1);
    }

    @Test
    void systemMessagesAreReservedForTheServerApi() {
        String id = f.conversations.openDirect(TENANT, "alice", "bob").id();
        MessageService.SendInput system = new MessageService.SendInput(null, ChatMessage.Type.SYSTEM, "notice", null,
                null, null, null);
        assertError(catchThrowable(() -> f.messages.send(TENANT, "alice", id, system)), ErrorCode.BAD_REQUEST);
        assertThat(f.messages.sendAsServer(TENANT, "alice", id, system).message().type()).isEqualTo(ChatMessage.Type.SYSTEM);
    }

    @Test
    void deactivatedUsersCannotSend() {
        String id = f.conversations.openDirect(TENANT, "alice", "bob").id();
        f.users.deactivate(TENANT, "alice");
        assertError(catchThrowable(() -> f.messages.send(TENANT, "alice", id, text(null, "x"))), ErrorCode.FORBIDDEN);
        assertThat(f.published.control).anySatisfy(c -> assertThat(c.type()).isEqualTo(ControlType.USER_DEACTIVATED));
    }

    @Test
    void quotaRejectionStillAcknowledgesAnAlreadyAcceptedRetry() {
        String id = f.conversations.openDirect(TENANT, "alice", "bob").id();
        MessageView accepted = f.messages.send(TENANT, "alice", id, text("c-1", "hi")).message();
        doThrow(DomainException.quotaExceeded("daily quota")).when(f.quotas).ensureMessageQuota(any());

        MessageService.SendResult retry = f.messages.send(TENANT, "alice", id, text("c-1", "hi"));
        assertThat(retry.duplicate()).isTrue();
        assertThat(retry.message().id()).isEqualTo(accepted.id());
        assertError(catchThrowable(() -> f.messages.send(TENANT, "alice", id, text("c-2", "new"))), ErrorCode.QUOTA_EXCEEDED);
    }

    @Test
    void onlyTheSenderEditsAndAdminsMayDelete() {
        ConversationView group = f.conversations.createGroup(TENANT, "alice", "Team", List.of("bob", "carol"), null, null);
        String messageId = f.messages.send(TENANT, "bob", group.id(), text(null, "draft")).message().id();

        assertError(catchThrowable(() -> f.messages.edit(TENANT, "carol", group.id(), messageId, "hacked")), ErrorCode.FORBIDDEN);
        assertThat(f.messages.edit(TENANT, "bob", group.id(), messageId, "final").edited()).isTrue();
        assertError(catchThrowable(() -> f.messages.delete(TENANT, "carol", group.id(), messageId)), ErrorCode.FORBIDDEN);
        MessageView deleted = f.messages.delete(TENANT, "alice", group.id(), messageId);
        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.content()).isNull();
    }

    @Test
    void disabledFeaturesAreRejectedWithTheirOwnCode() {
        f.features(EnumSet.noneOf(Feature.class));
        assertError(catchThrowable(() -> f.conversations.createGroup(TENANT, "alice", "Team", List.of("bob"), null, null)),
                ErrorCode.FEATURE_DISABLED);
        String id = f.conversations.openDirect(TENANT, "alice", "bob").id();
        String messageId = f.messages.send(TENANT, "alice", id, text(null, "x")).message().id();
        assertError(catchThrowable(() -> f.messages.react(TENANT, "bob", id, messageId, "👍")), ErrorCode.FEATURE_DISABLED);
    }

    @Test
    void groupSizeLimitIsEnforced() {
        f.limits(new TenantLimits(null, null, null, null, 2, null));
        assertError(catchThrowable(() -> f.conversations.createGroup(TENANT, "alice", "Team", List.of("bob", "carol"),
                null, null)), ErrorCode.QUOTA_EXCEEDED);
    }

    @Test
    void ownerLeavingHandsOwnershipToAnAdmin() {
        ConversationView group = f.conversations.createGroup(TENANT, "alice", "Team", List.of("bob", "carol"), null, null);
        f.membership.setRole(TENANT, "alice", group.id(), "carol", Participant.Role.ADMIN);

        f.membership.removeMember(TENANT, "alice", group.id(), "alice");

        ConversationView after = f.conversations.get(TENANT, "bob", group.id());
        assertThat(after.participants()).extracting(p -> p.userId() + ":" + p.role())
                .containsExactlyInAnyOrder("bob:MEMBER", "carol:OWNER");
        assertThat(f.published.ofType(ChatEvent.CONVERSATION_REMOVED)).singleElement()
                .satisfies(d -> assertThat(d.users()).containsExactly("alice"));
    }

    @Test
    void rejoiningMembersDoNotSeeOldHistory() {
        ConversationView group = f.conversations.createGroup(TENANT, "alice", "Team", List.of("bob"), null, null);
        f.messages.send(TENANT, "alice", group.id(), text(null, "before"));
        f.membership.addMembers(TENANT, "alice", group.id(), Set.of("carol"));
        f.messages.send(TENANT, "alice", group.id(), text(null, "after"));

        assertThat(f.messages.list(TENANT, "carol", group.id(), null, null, 50))
                .extracting(MessageView::content).doesNotContain("before").contains("after");
    }

    @Test
    void readWatermarksOnlyMoveForwardAndAreCapped() {
        String id = f.conversations.openDirect(TENANT, "alice", "bob").id();
        f.messages.send(TENANT, "alice", id, text(null, "1"));
        f.messages.send(TENANT, "alice", id, text(null, "2"));

        f.receipts.markRead(TENANT, "bob", id, 99);
        f.receipts.markRead(TENANT, "bob", id, 1);

        ConversationView view = f.conversations.get(TENANT, "bob", id);
        assertThat(view.participants()).filteredOn(p -> p.userId().equals("bob"))
                .singleElement().satisfies(p -> assertThat(p.readSeq()).isEqualTo(2));
        assertThat(view.unreadCount()).isZero();
    }

    @Test
    void receiptsStayPrivateWithoutTheReadReceiptsFeature() {
        String id = f.conversations.openDirect(TENANT, "alice", "bob").id();
        f.messages.send(TENANT, "alice", id, text(null, "1"));
        f.features(EnumSet.complementOf(EnumSet.of(Feature.READ_RECEIPTS)));
        f.published.clear();

        f.receipts.markRead(TENANT, "bob", id, 1);

        assertThat(f.published.ofType(ChatEvent.RECEIPT)).singleElement()
                .satisfies(d -> assertThat(d.users()).containsExactly("bob"));
    }
}
