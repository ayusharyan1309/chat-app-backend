package com.ayush.chat.chat;

import com.ayush.chat.chat.Views.ConversationView;
import com.ayush.chat.chat.Views.MessageView;
import com.ayush.chat.chat.Views.ParticipantView;
import com.ayush.chat.chat.Views.UserView;
import com.ayush.chat.chat.event.ChatEvent;
import com.ayush.chat.chat.event.DomainEvent;
import com.ayush.chat.core.config.TenantConnectors;
import com.ayush.chat.core.error.ApiException;
import com.ayush.chat.core.util.Ids;
import com.ayush.chat.store.MessageStore;
import com.ayush.chat.store.model.AppendResult;
import com.ayush.chat.store.model.ChatMessage;
import com.ayush.chat.store.model.ChatUser;
import com.ayush.chat.store.model.Conversation;
import com.ayush.chat.store.model.InboxItem;
import com.ayush.chat.store.model.Participant;
import com.ayush.chat.tenant.Feature;
import com.ayush.chat.tenant.TenantDirectory;
import com.ayush.chat.tenant.TenantSnapshot;
import com.ayush.chat.tenant.UsageService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The messaging domain: users, conversations, membership, messages, receipts and typing.
 * Every operation is tenant-scoped, feature-gated and quota-checked here, so REST, STOMP and
 * the server API behave identically.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final Pattern USER_ID = Pattern.compile("^[A-Za-z0-9_.@:+-]{1,128}$");
    private static final int MAX_PAGE = 200;

    private final TenantDirectory tenants;
    private final TenantConnectors connectors;
    private final UsageService usage;
    private final RealtimeGateway gateway;
    private final PresenceService presence;
    private final MetricsService metrics;

    private final Cache<String, Boolean> activeUsers = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30)).maximumSize(500_000).build();
    private final Cache<String, List<Participant>> participantCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(10)).maximumSize(100_000).build();
    /** Hot-path caches: block state and conversations (type/directKey never change; title is display-only). */
    private final Cache<String, Boolean> blockCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(10)).maximumSize(500_000).build();
    private final Cache<String, Conversation> conversationCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30)).maximumSize(200_000).build();

    public record UserInput(String id, String displayName, String email, String phone, String avatarUrl, String about,
                            Map<String, Object> metadata) {
    }

    public record SendInput(String clientMsgId, ChatMessage.Type type, String content, ChatMessage.Attachment attachment,
                            String replyToId, Boolean forwarded, Map<String, Object> metadata) {
    }

    public record SendResult(MessageView message, boolean duplicate) {
    }

    public record ReceiptView(String userId, long deliveredSeq, long readSeq) {
    }

    public record TypingView(String userId, boolean typing) {
    }

    private record Ctx(TenantSnapshot tenant, MessageStore store) {
        String slug() {
            return tenant.slug();
        }
    }

    private Ctx ctx(String slug) {
        TenantSnapshot tenant = tenants.requireUsable(slug);
        return new Ctx(tenant, connectors.store(slug));
    }

    // ================================================================== users

    public UserView upsertUser(String slug, UserInput input) {
        Ctx c = ctx(slug);
        if (input.id() == null || !USER_ID.matcher(input.id()).matches()) {
            throw ApiException.badRequest("id must be 1-128 chars of letters, digits and _ . @ : + -");
        }
        Optional<ChatUser> existing = c.store().findUser(c.slug(), input.id());
        if (existing.isEmpty()) {
            Long max = c.tenant().limits().maxUsers();
            if (max != null && c.store().countUsers(c.slug()) >= max) {
                throw ApiException.quotaExceeded("User limit of " + max + " reached for this tenant");
            }
        }
        Instant now = Instant.now();
        ChatUser base = existing.orElseGet(() -> ChatUser.builder().tenantId(c.slug()).id(input.id()).createdAt(now).build());
        ChatUser user = base.toBuilder()
                .displayName(firstNonBlank(input.displayName(), base.getDisplayName(), input.id()))
                .email(input.email() != null ? input.email() : base.getEmail())
                .phone(input.phone() != null ? input.phone() : base.getPhone())
                .avatarUrl(input.avatarUrl() != null ? input.avatarUrl() : base.getAvatarUrl())
                .about(input.about() != null ? input.about() : base.getAbout())
                .metadata(input.metadata() != null ? input.metadata() : base.getMetadata())
                .updatedAt(now)
                .build();
        ChatUser saved = c.store().upsertUser(user);
        activeUsers.invalidate(key(slug, saved.getId()));
        return userView(c, saved);
    }

    /** Just-in-time provisioning for external/dev logins; never overwrites an existing profile. */
    public ChatUser ensureUser(String slug, String userId, String displayName, String email) {
        Ctx c = ctx(slug);
        Optional<ChatUser> existing = c.store().findUser(slug, userId);
        if (existing.isPresent()) {
            if (existing.get().isDeactivated()) {
                throw ApiException.forbidden("User is deactivated");
            }
            return existing.get();
        }
        upsertUser(slug, new UserInput(userId, displayName, email, null, null, null, null));
        return c.store().findUser(slug, userId).orElseThrow();
    }

    public void deactivateUser(String slug, String userId) {
        Ctx c = ctx(slug);
        ChatUser user = c.store().findUser(slug, userId).orElseThrow(() -> ApiException.notFound("User '" + userId + "'"));
        c.store().upsertUser(user.toBuilder().deactivated(true).pushTokens(new LinkedHashSet<>()).updatedAt(Instant.now()).build());
        activeUsers.put(key(slug, userId), false);
        gateway.publishControl(new RealtimeGateway.ControlMessage("user.deactivated", slug, userId));
    }

    /** Throws unless the user exists and is active (cached briefly). */
    public void requireActiveUser(String slug, String userId) {
        Boolean active = activeUsers.get(key(slug, userId), k -> connectors.store(slug).findUser(slug, userId)
                .map(u -> !u.isDeactivated()).orElse(false));
        if (!Boolean.TRUE.equals(active)) {
            throw ApiException.forbidden("User '" + userId + "' does not exist or is deactivated");
        }
    }

    public void forgetUser(String slug, String userId) {
        activeUsers.invalidate(key(slug, userId));
    }

    public UserView getUser(String slug, String userId) {
        Ctx c = ctx(slug);
        return userView(c, c.store().findUser(slug, userId).orElseThrow(() -> ApiException.notFound("User '" + userId + "'")));
    }

    public UserView updateProfile(String slug, String userId, String displayName, String avatarUrl, String about) {
        Ctx c = ctx(slug);
        ChatUser user = c.store().findUser(slug, userId).orElseThrow(() -> ApiException.notFound("User"));
        ChatUser updated = c.store().upsertUser(user.toBuilder()
                .displayName(displayName != null && !displayName.isBlank() ? displayName.trim() : user.getDisplayName())
                .avatarUrl(avatarUrl != null ? avatarUrl : user.getAvatarUrl())
                .about(about != null ? about : user.getAbout())
                .updatedAt(Instant.now()).build());
        return userView(c, updated);
    }

    public void addDevice(String slug, String userId, String token) {
        if (token == null || token.isBlank() || token.length() > 4096) {
            throw ApiException.badRequest("A device token is required");
        }
        Ctx c = ctx(slug);
        ChatUser user = c.store().findUser(slug, userId).orElseThrow(() -> ApiException.notFound("User"));
        Set<String> tokens = new LinkedHashSet<>(user.getPushTokens());
        tokens.add(token);
        while (tokens.size() > 10) {
            tokens.remove(tokens.iterator().next());
        }
        c.store().upsertUser(user.toBuilder().pushTokens(tokens).updatedAt(Instant.now()).build());
    }

    public void removeDevices(String slug, String userId, Collection<String> tokens) {
        Ctx c = ctx(slug);
        c.store().findUser(slug, userId).ifPresent(user -> {
            Set<String> remaining = new LinkedHashSet<>(user.getPushTokens());
            if (remaining.removeAll(tokens)) {
                c.store().upsertUser(user.toBuilder().pushTokens(remaining).updatedAt(Instant.now()).build());
            }
        });
    }

    public List<UserView> searchUsers(String slug, String viewerId, String query, int offset, int limit, boolean adminView) {
        Ctx c = ctx(slug);
        if (!adminView && !Boolean.TRUE.equals(c.tenant().settings().allowUserSearch())) {
            throw ApiException.forbidden("User search is disabled for this tenant");
        }
        List<ChatUser> users = c.store().searchUsers(slug, query, Math.max(0, offset), clamp(limit, 1, MAX_PAGE));
        List<ChatUser> visible = users.stream()
                .filter(u -> adminView || (!u.isDeactivated() && !u.getId().equals(viewerId)))
                .toList();
        return userViews(c, visible);
    }

    public long countUsers(String slug) {
        Ctx c = ctx(slug);
        return c.store().countUsers(slug);
    }

    // ================================================================== blocks

    public List<String> listBlocked(String slug, String me) {
        return ctx(slug).store().listBlocked(slug, me);
    }

    public void setBlocked(String slug, String me, String other, boolean blocked) {
        if (me.equals(other)) {
            throw ApiException.badRequest("You cannot block yourself");
        }
        Ctx c = ctx(slug);
        if (blocked && c.store().findUser(slug, other).isEmpty()) {
            throw ApiException.notFound("User '" + other + "'");
        }
        c.store().setBlocked(slug, me, other, blocked);
        blockCache.invalidate(slug + "|" + me + "|" + other);
    }

    // ================================================================== conversations

    public ConversationView openDirect(String slug, String me, String otherId) {
        Ctx c = ctx(slug);
        if (otherId == null || otherId.equals(me)) {
            throw ApiException.badRequest("userId must be another user");
        }
        ChatUser other = c.store().findUser(slug, otherId).filter(u -> !u.isDeactivated())
                .orElseThrow(() -> ApiException.notFound("User '" + otherId + "'"));
        String directKey = Conversation.directKey(me, other.getId());
        Optional<Conversation> existing = c.store().findDirectConversation(slug, directKey);
        Conversation conversation = existing.orElseGet(() -> {
            Instant now = Instant.now();
            Conversation created = Conversation.builder().tenantId(slug).id(Ids.newId()).type(Conversation.Type.DIRECT)
                    .directKey(directKey).createdBy(me).createdAt(now).updatedAt(now).build();
            return c.store().createConversation(created, List.of(
                    participant(slug, created.getId(), me, Participant.Role.MEMBER, 0, now),
                    participant(slug, created.getId(), other.getId(), Participant.Role.MEMBER, 0, now)));
        });
        if (existing.isEmpty()) {
            List<Participant> members = c.store().listParticipants(slug, conversation.getId());
            notifyConversation(c, conversation, members);
            domainEvent(c, DomainEvent.CONVERSATION_CREATED, me, conversationPayload(conversation, members), List.of(), null);
        }
        return getConversation(slug, me, conversation.getId());
    }

    public ConversationView createGroup(String slug, String me, String title, Collection<String> memberIds,
                                        String description, String avatarUrl) {
        Ctx c = ctx(slug);
        c.tenant().require(Feature.GROUP_CHAT);
        if (title == null || title.isBlank() || title.length() > 255) {
            throw ApiException.badRequest("title is required (max 255 characters)");
        }
        Set<String> members = new LinkedHashSet<>(memberIds == null ? List.of() : memberIds);
        members.remove(me);
        checkGroupSize(c, members.size() + 1);
        requireUsersExist(c, members);
        Instant now = Instant.now();
        Conversation group = Conversation.builder().tenantId(slug).id(Ids.newId()).type(Conversation.Type.GROUP)
                .title(title.trim()).description(description).avatarUrl(avatarUrl).createdBy(me)
                .createdAt(now).updatedAt(now).build();
        List<Participant> participants = new ArrayList<>();
        participants.add(participant(slug, group.getId(), me, Participant.Role.OWNER, 0, now));
        members.forEach(m -> participants.add(participant(slug, group.getId(), m, Participant.Role.MEMBER, 0, now)));
        c.store().createConversation(group, participants);
        systemMessage(c, group, me, "group.created", List.of(), name(c, me) + " created the group \"" + group.getTitle() + "\"");
        Conversation fresh = c.store().findConversation(slug, group.getId()).orElseThrow();
        notifyConversation(c, fresh, participants);
        domainEvent(c, DomainEvent.CONVERSATION_CREATED, me, conversationPayload(fresh, participants), List.of(), null);
        return getConversation(slug, me, group.getId());
    }

    public List<ConversationView> inbox(String slug, String me, int limit, Instant before) {
        Ctx c = ctx(slug);
        List<InboxItem> items = c.store().listInbox(slug, me, clamp(limit, 1, 100), before);
        Set<String> peerIds = new LinkedHashSet<>();
        for (InboxItem item : items) {
            peerId(item.conversation(), me).ifPresent(peerIds::add);
        }
        Map<String, UserView> peers = userViews(c, c.store().findUsers(slug, peerIds)).stream()
                .collect(Collectors.toMap(UserView::id, v -> v, (a, b) -> a));
        return items.stream()
                .map(item -> Views.conversation(item.conversation(), item.self(),
                        peerId(item.conversation(), me).map(id -> peers.getOrDefault(id, UserView.unknown(id))).orElse(null),
                        null))
                .toList();
    }

    public ConversationView getConversation(String slug, String me, String conversationId) {
        Ctx c = ctx(slug);
        Conversation conversation = conversation(c, conversationId);
        Participant self = requireMember(c, conversationId, me);
        List<Participant> members = c.store().listParticipants(slug, conversationId);
        Map<String, ChatUser> users = c.store().findUsers(slug, members.stream().map(Participant::getUserId).toList())
                .stream().collect(Collectors.toMap(ChatUser::getId, u -> u));
        List<ParticipantView> participantViews = members.stream()
                .map(p -> {
                    ChatUser u = users.get(p.getUserId());
                    return new ParticipantView(p.getUserId(), p.getRole(), u != null ? u.getDisplayName() : p.getUserId(),
                            u != null ? u.getAvatarUrl() : null, p.getDeliveredSeq(), p.getReadSeq(), p.getJoinedAt());
                }).toList();
        UserView peer = peerId(conversation, me)
                .map(id -> users.containsKey(id) ? userView(c, users.get(id)) : UserView.unknown(id)).orElse(null);
        return Views.conversation(conversation, self, peer, participantViews);
    }

    public ConversationView updateGroup(String slug, String me, String conversationId, String title, String description,
                                        String avatarUrl) {
        Ctx c = ctx(slug);
        Conversation conversation = conversation(c, conversationId);
        requireGroup(conversation);
        requireAdmin(requireMember(c, conversationId, me));
        Conversation updated = conversation.toBuilder()
                .title(title != null && !title.isBlank() ? title.trim() : conversation.getTitle())
                .description(description != null ? description : conversation.getDescription())
                .avatarUrl(avatarUrl != null ? avatarUrl : conversation.getAvatarUrl())
                .updatedAt(Instant.now()).build();
        c.store().updateConversation(updated);
        conversationCache.invalidate(key(slug, conversationId));
        if (!Objects.equals(updated.getTitle(), conversation.getTitle())) {
            systemMessage(c, updated, me, "group.renamed", List.of(),
                    name(c, me) + " changed the group name to \"" + updated.getTitle() + "\"");
        }
        notifyConversation(c, c.store().findConversation(slug, conversationId).orElseThrow(),
                c.store().listParticipants(slug, conversationId));
        return getConversation(slug, me, conversationId);
    }

    public ConversationView updateSettings(String slug, String me, String conversationId, Boolean muted, Boolean archived,
                                           Boolean pinned) {
        Ctx c = ctx(slug);
        Participant self = requireMember(c, conversationId, me);
        c.store().upsertParticipant(self.toBuilder()
                .muted(muted != null ? muted : self.isMuted())
                .archived(archived != null ? archived : self.isArchived())
                .pinned(pinned != null ? pinned : self.isPinned())
                .updatedAt(Instant.now()).build());
        ConversationView view = getConversation(slug, me, conversationId);
        gateway.toUser(slug, me, ChatEvent.of(ChatEvent.CONVERSATION_UPDATED, conversationId, withoutParticipants(view)));
        return view;
    }

    public ConversationView addMembers(String slug, String me, String conversationId, Collection<String> userIds) {
        Ctx c = ctx(slug);
        c.tenant().require(Feature.GROUP_CHAT);
        Conversation conversation = conversation(c, conversationId);
        requireGroup(conversation);
        requireAdmin(requireMember(c, conversationId, me));
        List<Participant> current = c.store().listParticipants(slug, conversationId);
        Set<String> existing = current.stream().map(Participant::getUserId).collect(Collectors.toSet());
        Set<String> toAdd = new LinkedHashSet<>(userIds == null ? List.of() : userIds);
        toAdd.removeAll(existing);
        if (toAdd.isEmpty()) {
            return getConversation(slug, me, conversationId);
        }
        checkGroupSize(c, current.size() + toAdd.size());
        requireUsersExist(c, toAdd);
        Instant now = Instant.now();
        for (String userId : toAdd) {
            Participant rejoin = c.store().findParticipant(slug, conversationId, userId)
                    .map(p -> p.toBuilder().leftAt(null).role(Participant.Role.MEMBER).joinedSeq(conversation.getLastSeq())
                            .deliveredSeq(conversation.getLastSeq()).readSeq(conversation.getLastSeq()).joinedAt(now)
                            .updatedAt(now).build())
                    .orElse(participant(slug, conversationId, userId, Participant.Role.MEMBER, conversation.getLastSeq(), now));
            c.store().upsertParticipant(rejoin);
        }
        participantCache.invalidate(key(slug, conversationId));
        systemMessage(c, conversation, me, "members.added", List.copyOf(toAdd),
                name(c, me) + " added " + names(c, toAdd));
        List<Participant> members = c.store().listParticipants(slug, conversationId);
        Conversation fresh = c.store().findConversation(slug, conversationId).orElseThrow();
        notifyConversation(c, fresh, members);
        domainEvent(c, DomainEvent.MEMBER_ADDED, me, Map.of("conversationId", conversationId, "userIds", List.copyOf(toAdd),
                "actorId", me), List.of(), null);
        return getConversation(slug, me, conversationId);
    }

    public ConversationView setRole(String slug, String me, String conversationId, String userId, Participant.Role role) {
        Ctx c = ctx(slug);
        Conversation conversation = conversation(c, conversationId);
        requireGroup(conversation);
        requireAdmin(requireMember(c, conversationId, me));
        if (role == null || role == Participant.Role.OWNER) {
            throw ApiException.badRequest("role must be ADMIN or MEMBER");
        }
        Participant target = requireMember(c, conversationId, userId);
        if (target.getRole() == Participant.Role.OWNER) {
            throw ApiException.forbidden("The group owner's role cannot be changed");
        }
        c.store().upsertParticipant(target.toBuilder().role(role).updatedAt(Instant.now()).build());
        participantCache.invalidate(key(slug, conversationId));
        notifyConversation(c, conversation, c.store().listParticipants(slug, conversationId));
        return getConversation(slug, me, conversationId);
    }

    public void removeMember(String slug, String me, String conversationId, String userId) {
        Ctx c = ctx(slug);
        Conversation conversation = conversation(c, conversationId);
        requireGroup(conversation);
        Participant actor = requireMember(c, conversationId, me);
        Participant target = requireMember(c, conversationId, userId);
        boolean leaving = me.equals(userId);
        if (!leaving) {
            requireAdmin(actor);
            if (target.getRole() == Participant.Role.OWNER) {
                throw ApiException.forbidden("The group owner cannot be removed");
            }
        }
        Instant now = Instant.now();
        c.store().removeParticipant(slug, conversationId, userId, now);
        participantCache.invalidate(key(slug, conversationId));
        List<Participant> remaining = c.store().listParticipants(slug, conversationId);
        if (leaving && target.getRole() == Participant.Role.OWNER && !remaining.isEmpty()) {
            Participant heir = remaining.stream().filter(Participant::isAdmin).findFirst().orElse(remaining.get(0));
            c.store().upsertParticipant(heir.toBuilder().role(Participant.Role.OWNER).updatedAt(now).build());
            remaining = c.store().listParticipants(slug, conversationId);
        }
        if (!remaining.isEmpty()) {
            systemMessage(c, conversation, me, leaving ? "member.left" : "member.removed", List.of(userId),
                    leaving ? name(c, me) + " left" : name(c, me) + " removed " + name(c, userId));
            notifyConversation(c, c.store().findConversation(slug, conversationId).orElseThrow(), remaining);
        }
        gateway.toUser(slug, userId, ChatEvent.of(ChatEvent.CONVERSATION_REMOVED, conversationId,
                Map.of("conversationId", conversationId)));
        domainEvent(c, DomainEvent.MEMBER_REMOVED, me, Map.of("conversationId", conversationId, "userIds", List.of(userId),
                "actorId", me), List.of(), null);
    }

    // ================================================================== messages

    public SendResult send(String slug, String me, String conversationId, SendInput input) {
        Ctx c = ctx(slug);
        requireActiveUser(slug, me);
        Conversation conversation = cachedConversation(c, conversationId);
        // One fresh membership read serves both the authorisation check and the fan-out.
        List<Participant> members = c.store().listParticipants(slug, conversationId);
        if (members.stream().noneMatch(p -> p.getUserId().equals(me))) {
            throw ApiException.notFound("Conversation");
        }
        ChatMessage.Type type = input.type() == null ? ChatMessage.Type.TEXT : input.type();
        if (type == ChatMessage.Type.SYSTEM) {
            throw ApiException.badRequest("SYSTEM messages can only be sent through the server API");
        }
        validateContent(c, type, input);
        if (conversation.getType() == Conversation.Type.DIRECT) {
            String other = peerId(conversation, me).orElseThrow();
            if (blocked(c, other, me)) {
                throw ApiException.forbidden("You can't send messages to this user");
            }
            if (blocked(c, me, other)) {
                throw ApiException.forbidden("Unblock this user to send messages");
            }
        }
        return append(c, conversation, members, me, type, input, true);
    }

    private boolean blocked(Ctx c, String blocker, String blocked) {
        return blockCache.get(c.slug() + "|" + blocker + "|" + blocked,
                k -> c.store().isBlocked(c.slug(), blocker, blocked));
    }

    private Conversation cachedConversation(Ctx c, String conversationId) {
        Conversation cached = conversationCache.getIfPresent(key(c.slug(), conversationId));
        if (cached != null) {
            return cached;
        }
        Conversation loaded = conversation(c, conversationId);
        conversationCache.put(key(c.slug(), conversationId), loaded);
        return loaded;
    }

    /** Server API: send on behalf of a participant; SYSTEM messages allowed. */
    public SendResult sendAsServer(String slug, String senderId, String conversationId, SendInput input) {
        Ctx c = ctx(slug);
        Conversation conversation = conversation(c, conversationId);
        List<Participant> members = c.store().listParticipants(slug, conversationId);
        if (members.stream().noneMatch(p -> p.getUserId().equals(senderId))) {
            throw ApiException.notFound("Conversation");
        }
        ChatMessage.Type type = input.type() == null ? ChatMessage.Type.TEXT : input.type();
        validateContent(c, type, input);
        return append(c, conversation, members, senderId, type, input, type != ChatMessage.Type.SYSTEM);
    }

    private SendResult append(Ctx c, Conversation conversation, List<Participant> members, String sender,
                              ChatMessage.Type type, SendInput input, boolean metered) {
        if (input.replyToId() != null && c.store().findMessage(c.slug(), conversation.getId(), input.replyToId()).isEmpty()) {
            throw ApiException.badRequest("replyToId does not reference a message in this conversation");
        }
        if (metered) {
            usage.checkRate(c.tenant(), sender);
        }
        String clientMsgId = input.clientMsgId() == null || input.clientMsgId().isBlank() ? Ids.newId() : input.clientMsgId();
        if (clientMsgId.length() > 128) {
            throw ApiException.badRequest("clientMsgId must be at most 128 characters");
        }
        if (metered) {
            try {
                usage.ensureMessageQuota(c.tenant());
            } catch (ApiException quotaExceeded) {
                // A retry of a message accepted before the quota ran out must still be acknowledged.
                Optional<ChatMessage> accepted = input.clientMsgId() == null ? Optional.empty()
                        : c.store().listMessages(c.slug(), conversation.getId(), null, null, 100).stream()
                        .filter(m -> sender.equals(m.getSenderId()) && clientMsgId.equals(m.getClientMsgId())).findFirst();
                return accepted.map(m -> new SendResult(MessageView.of(m), true)).orElseThrow(() -> quotaExceeded);
            }
        }
        ChatMessage message = ChatMessage.builder()
                .tenantId(c.slug()).id(Ids.newId()).conversationId(conversation.getId()).senderId(sender)
                .clientMsgId(clientMsgId).type(type).content(input.content()).attachment(input.attachment())
                .replyToId(input.replyToId()).forwarded(Boolean.TRUE.equals(input.forwarded()))
                .metadata(input.metadata()).createdAt(Instant.now())
                .build();
        AppendResult result = c.store().appendMessage(message);
        MessageView view = MessageView.of(result.message());
        if (!result.duplicate()) {
            if (metered) {
                usage.countMessage(c.tenant());
            }
            metrics.messageSent();
            fanOutNewMessage(c, conversation, members, sender, view);
        }
        return new SendResult(view, result.duplicate());
    }

    private void fanOutNewMessage(Ctx c, Conversation conversation, List<Participant> members, String sender,
                                  MessageView view) {
        List<String> userIds = members.stream().map(Participant::getUserId).toList();
        gateway.toUsers(c.slug(), userIds, ChatEvent.of(ChatEvent.MESSAGE_NEW, conversation.getId(), view));
        List<String> pushRecipients = members.stream()
                .filter(p -> !p.getUserId().equals(sender) && !p.isMuted())
                .map(Participant::getUserId).toList();
        domainEvent(c, DomainEvent.MESSAGE_CREATED, sender, view, pushRecipients,
                conversation.getType() == Conversation.Type.GROUP ? conversation.getTitle() : null);
    }

    public MessageView edit(String slug, String me, String conversationId, String messageId, String content) {
        Ctx c = ctx(slug);
        c.tenant().require(Feature.MESSAGE_EDITING);
        requireMember(c, conversationId, me);
        ChatMessage message = message(c, conversationId, messageId);
        if (!me.equals(message.getSenderId())) {
            throw ApiException.forbidden("You can only edit your own messages");
        }
        if (message.isDeleted()) {
            throw ApiException.badRequest("Deleted messages cannot be edited");
        }
        int window = c.tenant().settings().editWindowMinutes();
        if (window > 0 && message.getCreatedAt().isBefore(Instant.now().minus(Duration.ofMinutes(window)))) {
            throw ApiException.forbidden("Messages can only be edited within " + window + " minutes");
        }
        validateText(c, content, true);
        message.setContent(content);
        message.setEdited(true);
        message.setEditedAt(Instant.now());
        c.store().updateMessage(message);
        return broadcastUpdate(c, message, DomainEvent.MESSAGE_UPDATED, me);
    }

    public MessageView delete(String slug, String me, String conversationId, String messageId) {
        Ctx c = ctx(slug);
        c.tenant().require(Feature.MESSAGE_DELETION);
        Participant self = requireMember(c, conversationId, me);
        ChatMessage message = message(c, conversationId, messageId);
        if (!me.equals(message.getSenderId()) && !self.isAdmin()) {
            throw ApiException.forbidden("You can only delete your own messages");
        }
        if (message.isDeleted()) {
            return MessageView.of(message);
        }
        message.setDeleted(true);
        message.setDeletedAt(Instant.now());
        message.setContent(null);
        message.setAttachment(null);
        message.setReactions(new LinkedHashMap<>());
        c.store().updateMessage(message);
        return broadcastUpdate(c, message, DomainEvent.MESSAGE_DELETED, me);
    }

    public MessageView react(String slug, String me, String conversationId, String messageId, String emoji) {
        Ctx c = ctx(slug);
        c.tenant().require(Feature.MESSAGE_REACTIONS);
        requireMember(c, conversationId, me);
        ChatMessage message = message(c, conversationId, messageId);
        if (message.isDeleted()) {
            throw ApiException.badRequest("Cannot react to a deleted message");
        }
        if (emoji == null) {
            message.removeReaction(me);
        } else {
            if (emoji.isBlank() || emoji.codePointCount(0, emoji.length()) > 8) {
                throw ApiException.badRequest("emoji must be a short emoji string");
            }
            message.addReaction(emoji, me);
        }
        c.store().updateMessage(message);
        return broadcastUpdate(c, message, DomainEvent.MESSAGE_UPDATED, me);
    }

    private MessageView broadcastUpdate(Ctx c, ChatMessage message, String domainType, String actor) {
        MessageView view = MessageView.of(message);
        List<String> members = c.store().listParticipants(c.slug(), message.getConversationId()).stream()
                .map(Participant::getUserId).toList();
        gateway.toUsers(c.slug(), members, ChatEvent.of(ChatEvent.MESSAGE_UPDATED, message.getConversationId(), view));
        domainEvent(c, domainType, actor, view, List.of(), null);
        return view;
    }

    public List<MessageView> messages(String slug, String me, String conversationId, Long after, Long before, int limit) {
        Ctx c = ctx(slug);
        Participant self = requireMember(c, conversationId, me);
        long floor = self.getJoinedSeq();
        int size = clamp(limit, 1, MAX_PAGE);
        List<ChatMessage> page = after != null
                ? c.store().listMessages(slug, conversationId, Math.max(after, floor), null, size)
                : c.store().listMessages(slug, conversationId, null, before, size);
        return page.stream().filter(m -> m.getSeq() > floor).map(MessageView::of).toList();
    }

    // ================================================================== receipts & typing

    public void markRead(String slug, String me, String conversationId, long seq) {
        receipt(slug, me, conversationId, null, seq);
    }

    public void markDelivered(String slug, String me, String conversationId, long seq) {
        receipt(slug, me, conversationId, seq, null);
    }

    private void receipt(String slug, String me, String conversationId, Long delivered, Long read) {
        Ctx c = ctx(slug);
        Conversation conversation = conversation(c, conversationId);
        Participant self = requireMember(c, conversationId, me);
        long cap = conversation.getLastSeq();
        Long d = delivered == null ? null : Math.min(delivered, cap);
        Long r = read == null ? null : Math.min(read, cap);
        if ((d == null || d <= self.getDeliveredSeq()) && (r == null || r <= self.getReadSeq())) {
            return;
        }
        c.store().advanceReadState(slug, conversationId, me, d, r);
        Participant updated = c.store().findParticipant(slug, conversationId, me).orElse(self);
        ChatEvent event = ChatEvent.of(ChatEvent.RECEIPT, conversationId,
                new ReceiptView(me, updated.getDeliveredSeq(), updated.getReadSeq()));
        if (c.tenant().has(Feature.READ_RECEIPTS)) {
            gateway.toUsers(slug, participantsCached(c, conversationId).stream().map(Participant::getUserId).toList(), event);
        } else {
            gateway.toUser(slug, me, event);
        }
    }

    public void typing(String slug, String me, String conversationId, boolean typing) {
        Ctx c = ctx(slug);
        if (!c.tenant().has(Feature.TYPING_INDICATORS)) {
            return;
        }
        List<Participant> members = participantsCached(c, conversationId);
        if (members.stream().noneMatch(p -> p.getUserId().equals(me))) {
            throw ApiException.forbidden("Not a member of this conversation");
        }
        List<String> others = members.stream().map(Participant::getUserId).filter(id -> !id.equals(me)).toList();
        gateway.toUsers(slug, others, ChatEvent.of(ChatEvent.TYPING, conversationId, new TypingView(me, typing)));
    }

    // ================================================================== helpers

    private List<Participant> participantsCached(Ctx c, String conversationId) {
        return participantCache.get(key(c.slug(), conversationId), k -> c.store().listParticipants(c.slug(), conversationId));
    }

    private Conversation conversation(Ctx c, String conversationId) {
        return c.store().findConversation(c.slug(), conversationId)
                .orElseThrow(() -> ApiException.notFound("Conversation"));
    }

    private ChatMessage message(Ctx c, String conversationId, String messageId) {
        return c.store().findMessage(c.slug(), conversationId, messageId)
                .orElseThrow(() -> ApiException.notFound("Message"));
    }

    private Participant requireMember(Ctx c, String conversationId, String userId) {
        return c.store().findParticipant(c.slug(), conversationId, userId).filter(Participant::isActive)
                .orElseThrow(() -> ApiException.notFound("Conversation"));
    }

    private static void requireAdmin(Participant p) {
        if (!p.isAdmin()) {
            throw ApiException.forbidden("Only group admins can do this");
        }
    }

    private static void requireGroup(Conversation conversation) {
        if (conversation.getType() != Conversation.Type.GROUP) {
            throw ApiException.badRequest("This operation is only available for groups");
        }
    }

    private void checkGroupSize(Ctx c, int size) {
        Integer max = c.tenant().limits().maxGroupSize();
        if (max != null && size > max) {
            throw ApiException.quotaExceeded("Groups are limited to " + max + " members on this plan");
        }
    }

    private void requireUsersExist(Ctx c, Collection<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        Set<String> found = c.store().findUsers(c.slug(), ids).stream().filter(u -> !u.isDeactivated())
                .map(ChatUser::getId).collect(Collectors.toSet());
        List<String> missing = ids.stream().filter(id -> !found.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("Unknown or deactivated users: " + String.join(", ", missing));
        }
    }

    private void validateContent(Ctx c, ChatMessage.Type type, SendInput input) {
        switch (type) {
            case IMAGE, VIDEO, AUDIO, STICKER -> {
                c.tenant().require(Feature.MEDIA_SHARING);
                requireAttachment(input);
                validateText(c, input.content(), false);
            }
            case FILE -> {
                c.tenant().require(Feature.FILE_SHARING);
                requireAttachment(input);
                validateText(c, input.content(), false);
            }
            case TEXT, SYSTEM -> validateText(c, input.content(), true);
            case LOCATION, CONTACT -> {
                if (input.metadata() == null || input.metadata().isEmpty()) {
                    throw ApiException.badRequest(type + " messages carry their payload in metadata");
                }
                validateText(c, input.content(), false);
            }
        }
    }

    private static void requireAttachment(SendInput input) {
        if (input.attachment() == null || input.attachment().getUrl() == null) {
            throw ApiException.badRequest("This message type requires an attachment with a url");
        }
    }

    private void validateText(Ctx c, String content, boolean required) {
        if (content == null || content.isBlank()) {
            if (required) {
                throw ApiException.badRequest("content is required");
            }
            return;
        }
        int max = c.tenant().settings().maxMessageLength();
        if (content.length() > max) {
            throw ApiException.badRequest("Message exceeds the maximum length of " + max + " characters");
        }
    }

    private void systemMessage(Ctx c, Conversation conversation, String actor, String event, List<String> targets, String text) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("event", event);
        metadata.put("actorId", actor);
        if (!targets.isEmpty()) {
            metadata.put("targetIds", targets);
        }
        ChatMessage message = ChatMessage.builder().tenantId(c.slug()).id(Ids.newId()).conversationId(conversation.getId())
                .senderId(actor).type(ChatMessage.Type.SYSTEM).content(text).metadata(metadata).createdAt(Instant.now())
                .build();
        AppendResult result = c.store().appendMessage(message);
        List<String> members = c.store().listParticipants(c.slug(), conversation.getId()).stream()
                .map(Participant::getUserId).toList();
        gateway.toUsers(c.slug(), members, ChatEvent.of(ChatEvent.MESSAGE_NEW, conversation.getId(),
                MessageView.of(result.message())));
    }

    /** Push each participant their own view of the conversation (title, unread and role differ). */
    private void notifyConversation(Ctx c, Conversation conversation, List<Participant> members) {
        Map<String, ChatUser> users = c.store().findUsers(c.slug(), members.stream().map(Participant::getUserId).toList())
                .stream().collect(Collectors.toMap(ChatUser::getId, u -> u));
        for (Participant p : members) {
            UserView peer = peerId(conversation, p.getUserId())
                    .map(id -> users.containsKey(id) ? userView(c, users.get(id)) : UserView.unknown(id)).orElse(null);
            gateway.toUser(c.slug(), p.getUserId(), ChatEvent.of(ChatEvent.CONVERSATION_UPDATED, conversation.getId(),
                    Views.conversation(conversation, p, peer, null)));
        }
        participantCache.invalidate(key(c.slug(), conversation.getId()));
    }

    private static ConversationView withoutParticipants(ConversationView v) {
        return new ConversationView(v.id(), v.type(), v.title(), v.description(), v.avatarUrl(), v.createdBy(), v.createdAt(),
                v.lastSeq(), v.lastMessage(), v.unreadCount(), v.muted(), v.archived(), v.pinned(), v.role(), v.peer(), null);
    }

    private Map<String, Object> conversationPayload(Conversation conversation, List<Participant> members) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", conversation.getId());
        data.put("type", conversation.getType());
        data.put("title", conversation.getTitle());
        data.put("createdBy", conversation.getCreatedBy());
        data.put("createdAt", conversation.getCreatedAt());
        data.put("participantIds", members.stream().map(Participant::getUserId).toList());
        return data;
    }

    private void domainEvent(Ctx c, String type, String actor, Object data, List<String> recipients, String conversationTitle) {
        boolean wantsWebhooks = c.tenant().has(Feature.WEBHOOKS);
        boolean wantsPush = c.tenant().has(Feature.PUSH_NOTIFICATIONS) && !recipients.isEmpty();
        if (!wantsWebhooks && !wantsPush) {
            return;
        }
        try {
            gateway.publishDomainEvent(new DomainEvent("evt_" + Ids.newId(), type, c.slug(), Instant.now(), data,
                    wantsPush ? recipients : List.of(), actor, wantsPush ? name(c, actor) : null, conversationTitle));
        } catch (Exception e) {
            log.error("Failed to publish domain event {} for {}", type, c.slug(), e);
        }
    }

    private static Optional<String> peerId(Conversation conversation, String me) {
        if (conversation.getType() != Conversation.Type.DIRECT || conversation.getDirectKey() == null) {
            return Optional.empty();
        }
        String[] pair = conversation.getDirectKey().split("\\|", 2);
        return Optional.of(pair[0].equals(me) ? pair[1] : pair[0]);
    }

    private static Participant participant(String slug, String conversationId, String userId, Participant.Role role,
                                           long joinedSeq, Instant at) {
        return Participant.builder().tenantId(slug).conversationId(conversationId).userId(userId).role(role)
                .joinedSeq(joinedSeq).deliveredSeq(joinedSeq).readSeq(joinedSeq).joinedAt(at).updatedAt(at).build();
    }

    private String name(Ctx c, String userId) {
        return c.store().findUser(c.slug(), userId).map(ChatUser::getDisplayName).filter(n -> !n.isBlank()).orElse(userId);
    }

    private String names(Ctx c, Collection<String> userIds) {
        Map<String, String> byId = new HashMap<>();
        c.store().findUsers(c.slug(), userIds).forEach(u -> byId.put(u.getId(), u.getDisplayName()));
        return userIds.stream().map(id -> byId.getOrDefault(id, id)).collect(Collectors.joining(", "));
    }

    private UserView userView(Ctx c, ChatUser user) {
        boolean presenceOn = c.tenant().has(Feature.PRESENCE);
        return UserView.of(user, presenceOn, presenceOn ? presence.isOnline(c.slug(), user.getId()) : null);
    }

    private List<UserView> userViews(Ctx c, List<ChatUser> users) {
        boolean presenceOn = c.tenant().has(Feature.PRESENCE);
        Map<String, Boolean> online = presenceOn
                ? presence.onlineMany(c.slug(), users.stream().map(ChatUser::getId).toList()) : Map.of();
        return users.stream().map(u -> UserView.of(u, presenceOn, presenceOn ? online.getOrDefault(u.getId(), false) : null))
                .toList();
    }

    private static String key(String a, String b) {
        return a + "::" + b;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
