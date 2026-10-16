package com.ayush.chat.chat.service;

import com.ayush.chat.chat.dto.command.SendMessageCommand;
import com.ayush.chat.chat.dto.command.SendMessageResult;
import com.ayush.chat.chat.dto.response.MessageView;
import com.ayush.chat.chat.event.ChatEvent;
import com.ayush.chat.chat.event.DomainEvent;
import com.ayush.chat.chat.mapper.ChatViewMapper;
import com.ayush.chat.core.cluster.DistributedLock;
import com.ayush.chat.core.exception.DomainException;
import com.ayush.chat.core.exception.QuotaExceededException;
import com.ayush.chat.core.util.Ids;
import com.ayush.chat.store.model.AppendResult;
import com.ayush.chat.store.model.ChatMessage;
import com.ayush.chat.store.model.Conversation;
import com.ayush.chat.store.model.Participant;
import com.ayush.chat.tenant.model.Feature;
import com.ayush.chat.tenant.service.QuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sending, editing, deleting, reacting to and paging messages. Every send gets the next dense
 * per-conversation {@code seq} from the store and is idempotent on {@code (sender, clientMsgId)}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private static final int MAX_PAGE = 200;
    /** How far back a quota-rejected retry looks for its already-accepted original. */
    private static final int RETRY_LOOKBACK = 100;

    private final TenantScopes scopes;
    private final ConversationAccess access;
    private final BlockService blocks;
    private final UserStatusService status;
    private final QuotaService quotas;
    private final MetricsService metrics;
    private final DistributedLock locks;
    private final ConversationNotifier notifier;

    /** A member sends a message. SYSTEM messages are reserved for the server API. */
    public SendMessageResult send(String tenant, String me, String conversationId, SendMessageCommand input) {
        TenantScope scope = scopes.of(tenant);
        status.requireActiveUser(scope.slug(), me);
        Conversation conversation = access.cached(scope, conversationId);
        // One fresh membership read serves both the authorisation check and the fan-out.
        List<Participant> members = scope.store().listParticipants(scope.slug(), conversationId);
        requireSender(members, me);
        ChatMessage.Type type = input.type() == null ? ChatMessage.Type.TEXT : input.type();
        if (type == ChatMessage.Type.SYSTEM) {
            throw DomainException.badRequest("SYSTEM messages can only be sent through the server API");
        }
        InputValidator.content(scope.tenant(), type, input.content(), input.attachment(), input.metadata());
        if (conversation.getType() == Conversation.Type.DIRECT) {
            String other = ChatViewMapper.peerId(conversation, me).orElseThrow();
            if (blocks.isBlocked(scope, other, me)) {
                throw DomainException.forbidden("You can't send messages to this user");
            }
            if (blocks.isBlocked(scope, me, other)) {
                throw DomainException.forbidden("Unblock this user to send messages");
            }
        }
        return append(scope, conversation, members, me, type, input);
    }

    /** Server API: send on behalf of a participant; SYSTEM messages allowed, blocks not applied. */
    public SendMessageResult sendAsServer(String tenant, String senderId, String conversationId, SendMessageCommand input) {
        TenantScope scope = scopes.of(tenant);
        Conversation conversation = access.require(scope, conversationId);
        List<Participant> members = scope.store().listParticipants(scope.slug(), conversationId);
        requireSender(members, senderId);
        ChatMessage.Type type = input.type() == null ? ChatMessage.Type.TEXT : input.type();
        InputValidator.content(scope.tenant(), type, input.content(), input.attachment(), input.metadata());
        return append(scope, conversation, members, senderId, type, input);
    }

    public MessageView edit(String tenant, String me, String conversationId, String messageId, String content) {
        TenantScope scope = scopes.of(tenant);
        scope.tenant().require(Feature.MESSAGE_EDITING);
        Participant self = access.requireMember(scope, conversationId, me);
        InputValidator.text(scope.tenant(), content, true);
        return locks.withLock(messageLock(scope, conversationId, messageId), () -> {
            ChatMessage message = visibleMessage(scope, self, conversationId, messageId);
            if (message.getType() == ChatMessage.Type.SYSTEM) {
                throw DomainException.forbidden("System notices cannot be edited");
            }
            if (!me.equals(message.getSenderId())) {
                throw DomainException.forbidden("You can only edit your own messages");
            }
            if (message.isDeleted()) {
                throw DomainException.badRequest("Deleted messages cannot be edited");
            }
            int window = scope.tenant().settings().editWindowMinutes();
            if (window > 0 && message.getCreatedAt().isBefore(Instant.now().minus(Duration.ofMinutes(window)))) {
                throw DomainException.forbidden("Messages can only be edited within " + window + " minutes");
            }
            message.setContent(content);
            message.setEdited(true);
            message.setEditedAt(Instant.now());
            scope.store().updateMessage(message);
            return broadcastUpdate(scope, message, DomainEvent.MESSAGE_UPDATED, me);
        });
    }

    /** Soft delete: content, attachment and reactions are erased; the seq stays so history has no gaps. */
    public MessageView delete(String tenant, String me, String conversationId, String messageId) {
        TenantScope scope = scopes.of(tenant);
        scope.tenant().require(Feature.MESSAGE_DELETION);
        Participant self = access.requireMember(scope, conversationId, me);
        return locks.withLock(messageLock(scope, conversationId, messageId), () -> {
            ChatMessage message = visibleMessage(scope, self, conversationId, messageId);
            if (message.getType() == ChatMessage.Type.SYSTEM) {
                throw DomainException.forbidden("System notices cannot be deleted");
            }
            if (!me.equals(message.getSenderId()) && !self.isAdmin()) {
                throw DomainException.forbidden("You can only delete your own messages");
            }
            if (message.isDeleted()) {
                return MessageView.of(message);
            }
            message.setDeleted(true);
            message.setDeletedAt(Instant.now());
            message.setContent(null);
            message.setAttachment(null);
            message.setReactions(new LinkedHashMap<>());
            scope.store().updateMessage(message);
            return broadcastUpdate(scope, message, DomainEvent.MESSAGE_DELETED, me);
        });
    }

    /** Set ({@code emoji} non-null) or clear the caller's single reaction. */
    public MessageView react(String tenant, String me, String conversationId, String messageId, String emoji) {
        TenantScope scope = scopes.of(tenant);
        scope.tenant().require(Feature.MESSAGE_REACTIONS);
        Participant self = access.requireMember(scope, conversationId, me);
        InputValidator.emoji(emoji);
        return locks.withLock(messageLock(scope, conversationId, messageId), () -> {
            ChatMessage message = visibleMessage(scope, self, conversationId, messageId);
            if (message.isDeleted()) {
                throw DomainException.badRequest("Cannot react to a deleted message");
            }
            if (emoji == null) {
                message.removeReaction(me);
            } else {
                message.addReaction(emoji, me);
            }
            scope.store().updateMessage(message);
            return broadcastUpdate(scope, message, DomainEvent.MESSAGE_UPDATED, me);
        });
    }

    /** A page of history: {@code after} pages forward (catch-up), otherwise backwards from {@code before}. */
    public List<MessageView> list(String tenant, String me, String conversationId, Long after, Long before, int limit) {
        TenantScope scope = scopes.of(tenant);
        Participant self = access.requireMember(scope, conversationId, me);
        long floor = self.getJoinedSeq();
        int size = InputValidator.clamp(limit, 1, MAX_PAGE);
        List<ChatMessage> page = after != null
                ? scope.store().listMessages(scope.slug(), conversationId, Math.max(after, floor), null, size)
                : scope.store().listMessages(scope.slug(), conversationId, null, before, size);
        return page.stream().filter(m -> m.getSeq() > floor).map(MessageView::of).toList();
    }

    /** Append a SYSTEM notice (group created, member added, ...) and deliver it. Not metered. */
    void postSystemNotice(TenantScope scope, Conversation conversation, String actor, String event, List<String> targets,
                          String text) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("event", event);
        metadata.put("actorId", actor);
        if (!targets.isEmpty()) {
            metadata.put("targetIds", targets);
        }
        ChatMessage message = ChatMessage.builder().tenantId(scope.slug()).id(Ids.newId()).conversationId(conversation.getId())
                .senderId(actor).type(ChatMessage.Type.SYSTEM).content(text).metadata(metadata).createdAt(Instant.now())
                .build();
        AppendResult result = scope.store().appendMessage(message);
        notifier.toMembers(scope, memberIds(scope, conversation.getId()),
                ChatEvent.of(ChatEvent.MESSAGE_NEW, conversation.getId(), MessageView.of(result.message())));
    }

    private SendMessageResult append(TenantScope scope, Conversation conversation, List<Participant> members, String sender,
                              ChatMessage.Type type, SendMessageCommand input) {
        if (input.replyToId() != null) {
            long floor = members.stream().filter(p -> p.getUserId().equals(sender)).mapToLong(Participant::getJoinedSeq)
                    .findFirst().orElse(Long.MAX_VALUE);
            boolean visible = scope.store().findMessage(scope.slug(), conversation.getId(), input.replyToId())
                    .filter(m -> m.getSeq() > floor).isPresent();
            if (!visible) {
                throw DomainException.badRequest("replyToId does not reference a message in this conversation");
            }
        }
        quotas.checkSendRate(scope.tenant(), sender);
        InputValidator.clientMsgId(input.clientMsgId());
        String clientMsgId = input.clientMsgId() == null || input.clientMsgId().isBlank() ? Ids.newId() : input.clientMsgId();
        try {
            quotas.ensureMessageQuota(scope.tenant());
        } catch (QuotaExceededException quotaExceeded) {
            // A retry of a message accepted before the quota ran out must still be acknowledged.
            return acceptedEarlier(scope, conversation, sender, input.clientMsgId())
                    .map(m -> new SendMessageResult(MessageView.of(m), true))
                    .orElseThrow(() -> quotaExceeded);
        }
        ChatMessage message = ChatMessage.builder()
                .tenantId(scope.slug()).id(Ids.newId()).conversationId(conversation.getId()).senderId(sender)
                .clientMsgId(clientMsgId).type(type).content(input.content()).attachment(input.attachment())
                .replyToId(input.replyToId()).forwarded(Boolean.TRUE.equals(input.forwarded()))
                .metadata(input.metadata()).createdAt(Instant.now())
                .build();
        AppendResult result = scope.store().appendMessage(message);
        MessageView view = MessageView.of(result.message());
        if (!result.duplicate()) {
            quotas.countMessage(scope.tenant());
            metrics.messageSent();
            fanOut(scope, conversation, members, sender, view);
        }
        return new SendMessageResult(view, result.duplicate());
    }

    private Optional<ChatMessage> acceptedEarlier(TenantScope scope, Conversation conversation, String sender,
                                                  String clientMsgId) {
        if (clientMsgId == null) {
            return Optional.empty();
        }
        return scope.store().listMessages(scope.slug(), conversation.getId(), null, null, RETRY_LOOKBACK).stream()
                .filter(m -> sender.equals(m.getSenderId()) && clientMsgId.equals(m.getClientMsgId()))
                .findFirst();
    }

    private void fanOut(TenantScope scope, Conversation conversation, List<Participant> members, String sender,
                        MessageView view) {
        try {
            notifier.toMembers(scope, members.stream().map(Participant::getUserId).toList(),
                    ChatEvent.of(ChatEvent.MESSAGE_NEW, conversation.getId(), view));
        } catch (Exception e) {
            // The message is durable; clients catch up with after=seq. Webhooks and push still go out.
            log.error("Realtime fan-out failed for {}/{}: {}", scope.slug(), conversation.getId(), e.getMessage());
        }
        List<String> pushRecipients = members.stream()
                .filter(p -> !p.getUserId().equals(sender) && !p.isMuted())
                .map(Participant::getUserId).toList();
        notifier.domainEvent(scope, DomainEvent.MESSAGE_CREATED, sender, view, pushRecipients,
                conversation.getType() == Conversation.Type.GROUP ? conversation.getTitle() : null);
    }

    private MessageView broadcastUpdate(TenantScope scope, ChatMessage message, String domainType, String actor) {
        MessageView view = MessageView.of(message);
        notifier.toMembers(scope, memberIds(scope, message.getConversationId()),
                ChatEvent.of(ChatEvent.MESSAGE_UPDATED, message.getConversationId(), view));
        notifier.domainEvent(scope, domainType, actor, view, List.of(), null);
        return view;
    }

    /** Messages from before a member joined do not exist for them. */
    private static ChatMessage visibleMessage(TenantScope scope, Participant self, String conversationId, String messageId) {
        return scope.store().findMessage(scope.slug(), conversationId, messageId)
                .filter(m -> m.getSeq() > self.getJoinedSeq())
                .orElseThrow(() -> DomainException.notFound("Message"));
    }

    private static void requireSender(List<Participant> members, String sender) {
        if (members.stream().noneMatch(p -> p.getUserId().equals(sender))) {
            throw DomainException.notFound("Conversation");
        }
    }

    private static List<String> memberIds(TenantScope scope, String conversationId) {
        return scope.store().listParticipants(scope.slug(), conversationId).stream().map(Participant::getUserId).toList();
    }

    private static String messageLock(TenantScope scope, String conversationId, String messageId) {
        return "msg:" + scope.slug() + ":" + conversationId + ":" + messageId;
    }
}
