package com.ayush.chat.chat.service;

import com.ayush.chat.chat.dto.response.ConversationView;
import com.ayush.chat.chat.dto.response.UserView;
import com.ayush.chat.chat.event.DomainEvent;
import com.ayush.chat.chat.mapper.ChatViewMapper;
import com.ayush.chat.core.cluster.DistributedLock;
import com.ayush.chat.core.exception.DomainException;
import com.ayush.chat.core.util.Ids;
import com.ayush.chat.store.model.ChatUser;
import com.ayush.chat.store.model.Conversation;
import com.ayush.chat.store.model.InboxItem;
import com.ayush.chat.store.model.Participant;
import com.ayush.chat.tenant.model.Feature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Creating, listing and updating conversations (membership changes live in {@link MembershipService}). */
@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final int MAX_INBOX_PAGE = 100;

    private final TenantScopes scopes;
    private final ConversationAccess access;
    private final ChatViewMapper views;
    private final ConversationNotifier notifier;
    private final MessageService messages;
    private final DistributedLock locks;

    /** The direct conversation between {@code me} and {@code otherId}, created on first use. */
    public ConversationView openDirect(String tenant, String me, String otherId) {
        TenantScope scope = scopes.of(tenant);
        if (otherId == null || otherId.equals(me)) {
            throw DomainException.badRequest("userId must be another user");
        }
        ChatUser other = scope.store().findUser(scope.slug(), otherId).filter(u -> !u.isDeactivated())
                .orElseThrow(() -> DomainException.notFound("User '" + otherId + "'"));
        String directKey = Conversation.directKey(me, other.getId());
        Optional<Conversation> existing = scope.store().findDirectConversation(scope.slug(), directKey);
        if (existing.isPresent()) {
            return get(tenant, me, existing.get().getId());
        }
        Instant now = Instant.now();
        Conversation draft = Conversation.builder().tenantId(scope.slug()).id(Ids.newId()).type(Conversation.Type.DIRECT)
                .directKey(directKey).createdBy(me).createdAt(now).updatedAt(now).build();
        Conversation created = scope.store().createConversation(draft, List.of(
                MembershipService.participant(scope.slug(), draft.getId(), me, Participant.Role.MEMBER, 0, now),
                MembershipService.participant(scope.slug(), draft.getId(), other.getId(), Participant.Role.MEMBER, 0, now)));
        // The store returns the conversation that won a concurrent create, so re-read its members.
        List<Participant> members = scope.store().listParticipants(scope.slug(), created.getId());
        notifier.conversationUpdated(scope, created, members);
        notifier.domainEvent(scope, DomainEvent.CONVERSATION_CREATED, me,
                ConversationNotifier.conversationPayload(created, members), List.of(), null);
        return get(tenant, me, created.getId());
    }

    public ConversationView createGroup(String tenant, String me, String title, Collection<String> memberIds,
                                        String description, String avatarUrl) {
        TenantScope scope = scopes.of(tenant);
        scope.tenant().require(Feature.GROUP_CHAT);
        InputValidator.groupTitle(title);
        InputValidator.groupDetails(title, description, avatarUrl);
        Set<String> members = new LinkedHashSet<>(memberIds == null ? List.of() : memberIds);
        members.remove(me);
        MembershipService.checkGroupSize(scope, members.size() + 1);
        MembershipService.requireUsersExist(scope, members);
        Instant now = Instant.now();
        Conversation group = Conversation.builder().tenantId(scope.slug()).id(Ids.newId()).type(Conversation.Type.GROUP)
                .title(title.trim()).description(description).avatarUrl(avatarUrl).createdBy(me)
                .createdAt(now).updatedAt(now).build();
        List<Participant> participants = new ArrayList<>();
        participants.add(MembershipService.participant(scope.slug(), group.getId(), me, Participant.Role.OWNER, 0, now));
        members.forEach(m -> participants.add(
                MembershipService.participant(scope.slug(), group.getId(), m, Participant.Role.MEMBER, 0, now)));
        scope.store().createConversation(group, participants);
        messages.postSystemNotice(scope, group, me, "group.created", List.of(),
                views.displayName(scope, me) + " created the group \"" + group.getTitle() + "\"");
        Conversation fresh = access.require(scope, group.getId());
        notifier.conversationUpdated(scope, fresh, participants);
        notifier.domainEvent(scope, DomainEvent.CONVERSATION_CREATED, me,
                ConversationNotifier.conversationPayload(fresh, participants), List.of(), null);
        return get(tenant, me, group.getId());
    }

    /** The caller's conversations, most recent activity first. */
    public List<ConversationView> inbox(String tenant, String me, int limit, Instant before) {
        TenantScope scope = scopes.of(tenant);
        List<InboxItem> items = scope.store().listInbox(scope.slug(), me, InputValidator.clamp(limit, 1, MAX_INBOX_PAGE), before);
        Set<String> peerIds = new LinkedHashSet<>();
        items.forEach(item -> ChatViewMapper.peerId(item.conversation(), me).ifPresent(peerIds::add));
        Map<String, UserView> peers = views.users(scope, scope.store().findUsers(scope.slug(), peerIds)).stream()
                .collect(Collectors.toMap(UserView::id, Function.identity(), (a, b) -> a));
        return items.stream()
                .map(item -> views.summary(item.conversation(), item.self(), ChatViewMapper.peerId(item.conversation(), me)
                        .map(id -> peers.getOrDefault(id, UserView.unknown(id))).orElse(null)))
                .toList();
    }

    public ConversationView get(String tenant, String me, String conversationId) {
        TenantScope scope = scopes.of(tenant);
        Conversation conversation = access.require(scope, conversationId);
        Participant self = access.requireMember(scope, conversationId, me);
        return views.detailed(scope, conversation, me, self, scope.store().listParticipants(scope.slug(), conversationId));
    }

    /** Group admins change title, description or avatar; a rename is announced in the chat. */
    public ConversationView updateGroup(String tenant, String me, String conversationId, String title, String description,
                                        String avatarUrl) {
        InputValidator.groupDetails(title, description, avatarUrl);
        TenantScope scope = scopes.of(tenant);
        Conversation conversation = access.require(scope, conversationId);
        ConversationAccess.requireGroup(conversation);
        ConversationAccess.requireAdmin(access.requireMember(scope, conversationId, me));
        Conversation updated = conversation.toBuilder()
                .title(title != null && !title.isBlank() ? title.trim() : conversation.getTitle())
                .description(description != null ? description : conversation.getDescription())
                .avatarUrl(avatarUrl != null ? avatarUrl : conversation.getAvatarUrl())
                .updatedAt(Instant.now()).build();
        scope.store().updateConversation(updated);
        notifier.conversationChanged(scope.slug(), conversationId);
        if (!Objects.equals(updated.getTitle(), conversation.getTitle())) {
            messages.postSystemNotice(scope, updated, me, "group.renamed", List.of(),
                    views.displayName(scope, me) + " changed the group name to \"" + updated.getTitle() + "\"");
        }
        notifier.conversationUpdated(scope, access.require(scope, conversationId),
                scope.store().listParticipants(scope.slug(), conversationId));
        return get(tenant, me, conversationId);
    }

    /** The caller's own mute / archive / pin flags. */
    public ConversationView updateSettings(String tenant, String me, String conversationId, Boolean muted, Boolean archived,
                                           Boolean pinned) {
        return locks.withLock(ConversationAccess.membersLock(tenant, conversationId), () -> {
            TenantScope scope = scopes.of(tenant);
            Participant self = access.requireMember(scope, conversationId, me);
            scope.store().upsertParticipant(self.toBuilder()
                    .muted(muted != null ? muted : self.isMuted())
                    .archived(archived != null ? archived : self.isArchived())
                    .pinned(pinned != null ? pinned : self.isPinned())
                    .updatedAt(Instant.now()).build());
            ConversationView view = get(tenant, me, conversationId);
            notifier.settingsChanged(scope, me, view);
            return view;
        });
    }
}
