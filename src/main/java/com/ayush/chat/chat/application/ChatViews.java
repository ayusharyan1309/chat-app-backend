package com.ayush.chat.chat.application;

import com.ayush.chat.chat.Views.LastMessage;
import com.ayush.chat.chat.Views.ConversationView;
import com.ayush.chat.chat.Views.ParticipantView;
import com.ayush.chat.chat.Views.UserView;
import com.ayush.chat.chat.port.PresenceQuery;
import com.ayush.chat.store.model.ChatUser;
import com.ayush.chat.store.model.Conversation;
import com.ayush.chat.store.model.Participant;
import com.ayush.chat.tenant.Feature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the public views of users and conversations, applying the tenant's feature switches
 * (presence, read receipts) so every API and event renders them identically.
 */
@Component
@RequiredArgsConstructor
public class ChatViews {

    private final PresenceQuery presence;

    public UserView user(TenantScope scope, ChatUser user) {
        boolean presenceOn = scope.tenant().has(Feature.PRESENCE);
        return UserView.of(user, presenceOn, presenceOn ? presence.isOnline(scope.slug(), user.getId()) : null);
    }

    public List<UserView> users(TenantScope scope, List<ChatUser> users) {
        boolean presenceOn = scope.tenant().has(Feature.PRESENCE);
        Map<String, Boolean> online = presenceOn
                ? presence.onlineMany(scope.slug(), users.stream().map(ChatUser::getId).toList()) : Map.of();
        return users.stream()
                .map(u -> UserView.of(u, presenceOn, presenceOn ? online.getOrDefault(u.getId(), false) : null))
                .toList();
    }

    /** The conversation as {@code viewer} sees it, with the full participant list. */
    public ConversationView detailed(TenantScope scope, Conversation conversation, String viewer, Participant self,
                                     List<Participant> members) {
        Map<String, ChatUser> users = usersById(scope, members.stream().map(Participant::getUserId).toList());
        boolean receipts = scope.tenant().has(Feature.READ_RECEIPTS);
        List<ParticipantView> participants = members.stream().map(p -> {
            ChatUser u = users.get(p.getUserId());
            // Without the read-receipts feature, users only see their own watermarks.
            boolean showWatermarks = receipts || p.getUserId().equals(viewer);
            return new ParticipantView(p.getUserId(), p.getRole(), u != null ? u.getDisplayName() : p.getUserId(),
                    u != null ? u.getAvatarUrl() : null, showWatermarks ? p.getDeliveredSeq() : 0,
                    showWatermarks ? p.getReadSeq() : 0, p.getJoinedAt());
        }).toList();
        return conversationView(conversation, self, peer(scope, conversation, viewer, users), participants);
    }

    /** One summary view per member (title, unread count and role differ per viewer). */
    public Map<Participant, ConversationView> summaries(TenantScope scope, Conversation conversation, List<Participant> members) {
        Map<String, ChatUser> users = usersById(scope, members.stream().map(Participant::getUserId).toList());
        Map<Participant, ConversationView> views = new LinkedHashMap<>();
        for (Participant p : members) {
            views.put(p, conversationView(conversation, p, peer(scope, conversation, p.getUserId(), users), null));
        }
        return views;
    }

    public ConversationView summary(Conversation conversation, Participant self, UserView peer) {
        return conversationView(conversation, self, peer, null);
    }

    public static ConversationView withoutParticipants(ConversationView v) {
        return new ConversationView(v.id(), v.type(), v.title(), v.description(), v.avatarUrl(), v.createdBy(), v.createdAt(),
                v.lastSeq(), v.lastMessage(), v.unreadCount(), v.muted(), v.archived(), v.pinned(), v.role(), v.peer(), null);
    }

    public String displayName(TenantScope scope, String userId) {
        return scope.store().findUser(scope.slug(), userId).map(ChatUser::getDisplayName)
                .filter(n -> !n.isBlank()).orElse(userId);
    }

    public String displayNames(TenantScope scope, Collection<String> userIds) {
        Map<String, ChatUser> byId = usersById(scope, userIds);
        return userIds.stream()
                .map(id -> Optional.ofNullable(byId.get(id)).map(ChatUser::getDisplayName).orElse(id))
                .collect(Collectors.joining(", "));
    }

    /** The other participant of a direct conversation. */
    public static Optional<String> peerId(Conversation conversation, String me) {
        if (conversation.getType() != Conversation.Type.DIRECT || conversation.getDirectKey() == null) {
            return Optional.empty();
        }
        String[] pair = conversation.getDirectKey().split("\\|", 2);
        return Optional.of(pair[0].equals(me) ? pair[1] : pair[0]);
    }

    private UserView peer(TenantScope scope, Conversation conversation, String viewer, Map<String, ChatUser> users) {
        return peerId(conversation, viewer)
                .map(id -> users.containsKey(id) ? user(scope, users.get(id)) : UserView.unknown(id))
                .orElse(null);
    }

    Map<String, ChatUser> usersById(TenantScope scope, Collection<String> ids) {
        return scope.store().findUsers(scope.slug(), ids).stream()
                .collect(Collectors.toMap(ChatUser::getId, Function.identity(), (a, b) -> a));
    }

    /** Summary as seen by {@code self}; unread count is relative to their read watermark. */
    private static ConversationView conversationView(Conversation c, Participant self, UserView peer,
                                                List<ParticipantView> participants) {
        LastMessage last = c.getLastMessageId() == null ? null
                : new LastMessage(c.getLastMessageId(), c.getLastMessagePreview(), c.getLastMessageSenderId(), c.getLastMessageAt());
        long floor = self == null ? c.getLastSeq() : Math.max(self.getReadSeq(), self.getJoinedSeq());
        String title = c.getType() == Conversation.Type.DIRECT && peer != null ? peer.displayName() : c.getTitle();
        String avatar = c.getType() == Conversation.Type.DIRECT && peer != null ? peer.avatarUrl() : c.getAvatarUrl();
        return new ConversationView(c.getId(), c.getType(), title, c.getDescription(), avatar, c.getCreatedBy(),
                c.getCreatedAt(), c.getLastSeq(), last, Math.max(0, c.getLastSeq() - floor),
                self != null && self.isMuted(), self != null && self.isArchived(), self != null && self.isPinned(),
                self == null ? null : self.getRole(), peer, participants);
    }
}
