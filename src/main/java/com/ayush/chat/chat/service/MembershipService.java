package com.ayush.chat.chat.service;

import com.ayush.chat.chat.dto.response.ConversationView;
import com.ayush.chat.chat.event.DomainEvent;
import com.ayush.chat.chat.mapper.ChatViewMapper;
import com.ayush.chat.core.cluster.DistributedLock;
import com.ayush.chat.core.exception.DomainException;
import com.ayush.chat.store.model.ChatUser;
import com.ayush.chat.store.model.Conversation;
import com.ayush.chat.store.model.Participant;
import com.ayush.chat.tenant.model.Feature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Group membership: adding, removing and promoting members. Every change runs under the
 * conversation's members lock, so concurrent admins cannot exceed the group limit or orphan a group.
 */
@Service
@RequiredArgsConstructor
public class MembershipService {

    private final TenantScopes scopes;
    private final ConversationAccess access;
    private final ChatViewMapper views;
    private final ConversationNotifier notifier;
    private final MessageService messages;
    private final ConversationService conversations;
    private final DistributedLock locks;

    public ConversationView addMembers(String tenant, String me, String conversationId, Collection<String> userIds) {
        return locks.withLock(ConversationAccess.membersLock(tenant, conversationId), () -> {
            TenantScope scope = scopes.of(tenant);
            scope.tenant().require(Feature.GROUP_CHAT);
            Conversation conversation = access.require(scope, conversationId);
            ConversationAccess.requireGroup(conversation);
            ConversationAccess.requireAdmin(access.requireMember(scope, conversationId, me));
            List<Participant> current = scope.store().listParticipants(scope.slug(), conversationId);
            Set<String> existing = current.stream().map(Participant::getUserId).collect(Collectors.toSet());
            Set<String> toAdd = new LinkedHashSet<>(userIds == null ? List.of() : userIds);
            toAdd.removeAll(existing);
            if (toAdd.isEmpty()) {
                return conversations.get(tenant, me, conversationId);
            }
            checkGroupSize(scope, current.size() + toAdd.size());
            requireUsersExist(scope, toAdd);
            Instant now = Instant.now();
            long seq = conversation.getLastSeq();
            for (String userId : toAdd) {
                // Rejoining members start fresh: history from their earlier membership stays hidden.
                Participant member = scope.store().findParticipant(scope.slug(), conversationId, userId)
                        .map(p -> p.toBuilder().leftAt(null).role(Participant.Role.MEMBER).joinedSeq(seq)
                                .deliveredSeq(seq).readSeq(seq).joinedAt(now).updatedAt(now).build())
                        .orElse(participant(scope.slug(), conversationId, userId, Participant.Role.MEMBER, seq, now));
                scope.store().upsertParticipant(member);
            }
            access.forgetMembers(scope.slug(), conversationId);
            messages.postSystemNotice(scope, conversation, me, "members.added", List.copyOf(toAdd),
                    views.displayName(scope, me) + " added " + views.displayNames(scope, toAdd));
            notifier.conversationUpdated(scope, access.require(scope, conversationId),
                    scope.store().listParticipants(scope.slug(), conversationId));
            notifier.domainEvent(scope, DomainEvent.MEMBER_ADDED, me, Map.of("conversationId", conversationId,
                    "userIds", List.copyOf(toAdd), "actorId", me), List.of(), null);
            return conversations.get(tenant, me, conversationId);
        });
    }

    public ConversationView setRole(String tenant, String me, String conversationId, String userId, Participant.Role role) {
        if (role == null || role == Participant.Role.OWNER) {
            throw DomainException.badRequest("role must be ADMIN or MEMBER");
        }
        return locks.withLock(ConversationAccess.membersLock(tenant, conversationId), () -> {
            TenantScope scope = scopes.of(tenant);
            Conversation conversation = access.require(scope, conversationId);
            ConversationAccess.requireGroup(conversation);
            ConversationAccess.requireAdmin(access.requireMember(scope, conversationId, me));
            Participant target = access.requireMember(scope, conversationId, userId);
            if (target.getRole() == Participant.Role.OWNER) {
                throw DomainException.forbidden("The group owner's role cannot be changed");
            }
            scope.store().upsertParticipant(target.toBuilder().role(role).updatedAt(Instant.now()).build());
            access.forgetMembers(scope.slug(), conversationId);
            notifier.conversationUpdated(scope, conversation, scope.store().listParticipants(scope.slug(), conversationId));
            return conversations.get(tenant, me, conversationId);
        });
    }

    /** Remove {@code userId}, or leave when it is the caller. An owner who leaves hands ownership on. */
    public void removeMember(String tenant, String me, String conversationId, String userId) {
        locks.withLock(ConversationAccess.membersLock(tenant, conversationId), () -> {
            TenantScope scope = scopes.of(tenant);
            Conversation conversation = access.require(scope, conversationId);
            ConversationAccess.requireGroup(conversation);
            Participant actor = access.requireMember(scope, conversationId, me);
            Participant target = access.requireMember(scope, conversationId, userId);
            boolean leaving = me.equals(userId);
            if (!leaving) {
                ConversationAccess.requireAdmin(actor);
                if (target.getRole() == Participant.Role.OWNER) {
                    throw DomainException.forbidden("The group owner cannot be removed");
                }
            }
            Instant now = Instant.now();
            scope.store().removeParticipant(scope.slug(), conversationId, userId, now);
            access.forgetMembers(scope.slug(), conversationId);
            List<Participant> remaining = scope.store().listParticipants(scope.slug(), conversationId);
            if (leaving && target.getRole() == Participant.Role.OWNER && !remaining.isEmpty()) {
                Participant heir = remaining.stream().filter(Participant::isAdmin).findFirst().orElse(remaining.get(0));
                scope.store().upsertParticipant(heir.toBuilder().role(Participant.Role.OWNER).updatedAt(now).build());
                remaining = scope.store().listParticipants(scope.slug(), conversationId);
            }
            if (!remaining.isEmpty()) {
                String text = leaving ? views.displayName(scope, me) + " left"
                        : views.displayName(scope, me) + " removed " + views.displayName(scope, userId);
                messages.postSystemNotice(scope, conversation, me, leaving ? "member.left" : "member.removed",
                        List.of(userId), text);
                notifier.conversationUpdated(scope, access.require(scope, conversationId), remaining);
            }
            notifier.conversationChanged(scope.slug(), conversationId);
            notifier.removedFrom(scope, userId, conversationId);
            notifier.domainEvent(scope, DomainEvent.MEMBER_REMOVED, me, Map.of("conversationId", conversationId,
                    "userIds", List.of(userId), "actorId", me), List.of(), null);
        });
    }

    static void checkGroupSize(TenantScope scope, int size) {
        Integer max = scope.tenant().limits().maxGroupSize();
        if (max != null && size > max) {
            throw DomainException.quotaExceeded("Groups are limited to " + max + " members on this plan");
        }
    }

    static void requireUsersExist(TenantScope scope, Collection<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        Set<String> found = scope.store().findUsers(scope.slug(), ids).stream().filter(u -> !u.isDeactivated())
                .map(ChatUser::getId).collect(Collectors.toSet());
        List<String> missing = ids.stream().filter(id -> !found.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw DomainException.badRequest("Unknown or deactivated users: " + String.join(", ", missing));
        }
    }

    static Participant participant(String tenant, String conversationId, String userId, Participant.Role role,
                                   long joinedSeq, Instant at) {
        return Participant.builder().tenantId(tenant).conversationId(conversationId).userId(userId).role(role)
                .joinedSeq(joinedSeq).deliveredSeq(joinedSeq).readSeq(joinedSeq).joinedAt(at).updatedAt(at).build();
    }
}
