package com.ayush.chat.chat.service;

import com.ayush.chat.chat.dto.response.ConversationView;
import com.ayush.chat.chat.event.ChatEvent;
import com.ayush.chat.chat.event.ControlMessage;
import com.ayush.chat.chat.event.DomainEvent;
import com.ayush.chat.chat.mapper.ChatViewMapper;
import com.ayush.chat.chat.port.RealtimePublisher;
import com.ayush.chat.core.util.Ids;
import com.ayush.chat.store.model.Conversation;
import com.ayush.chat.store.model.Participant;
import com.ayush.chat.tenant.model.Feature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Side effects that follow a successful change: realtime events to participants, cluster cache
 * invalidation and domain events for webhooks and push. Publishing is best effort — the change is
 * already durable, and clients resynchronise from the store.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationNotifier {

    private final RealtimePublisher publisher;
    private final ChatViewMapper views;
    private final ConversationAccess access;

    /** Push every member their own summary of the conversation and invalidate caches cluster-wide. */
    public void conversationUpdated(TenantScope scope, Conversation conversation, List<Participant> members) {
        views.summaries(scope, conversation, members).forEach((member, view) -> publisher.toUser(scope.slug(),
                member.getUserId(), ChatEvent.of(ChatEvent.CONVERSATION_UPDATED, conversation.getId(), view)));
        conversationChanged(scope.slug(), conversation.getId());
    }

    public void conversationChanged(String tenant, String conversationId) {
        access.forget(tenant, conversationId);
        control(ControlMessage.conversationChanged(tenant, conversationId));
    }

    public void settingsChanged(TenantScope scope, String userId, ConversationView view) {
        publisher.toUser(scope.slug(), userId,
                ChatEvent.of(ChatEvent.CONVERSATION_UPDATED, view.id(), ChatViewMapper.withoutParticipants(view)));
    }

    public void removedFrom(TenantScope scope, String userId, String conversationId) {
        publisher.toUser(scope.slug(), userId, ChatEvent.of(ChatEvent.CONVERSATION_REMOVED, conversationId,
                Map.of("conversationId", conversationId)));
    }

    public void toMembers(TenantScope scope, List<String> userIds, ChatEvent event) {
        publisher.toUsers(scope.slug(), userIds, event);
    }

    public void control(ControlMessage message) {
        try {
            publisher.publishControl(message);
        } catch (Exception e) {
            log.warn("Could not broadcast {} for {}: {}", message.type(), message.tenant(), e.getMessage());
        }
    }

    /**
     * Publish a domain event when the tenant consumes it (webhooks, or push for {@code recipients}).
     * The sender's display name is only resolved when a push will use it.
     */
    public void domainEvent(TenantScope scope, String type, String actor, Object data, List<String> recipients,
                            String conversationTitle) {
        boolean wantsWebhooks = scope.tenant().has(Feature.WEBHOOKS);
        boolean wantsPush = scope.tenant().has(Feature.PUSH_NOTIFICATIONS) && !recipients.isEmpty();
        if (!wantsWebhooks && !wantsPush) {
            return;
        }
        try {
            publisher.publishDomainEvent(new DomainEvent("evt_" + Ids.newId(), type, scope.slug(), Instant.now(), data,
                    wantsPush ? recipients : List.of(), actor, wantsPush ? views.displayName(scope, actor) : null,
                    conversationTitle));
        } catch (Exception e) {
            log.error("Failed to publish domain event {} for {}", type, scope.slug(), e);
        }
    }

    public static Map<String, Object> conversationPayload(Conversation conversation, List<Participant> members) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", conversation.getId());
        data.put("type", conversation.getType());
        data.put("title", conversation.getTitle());
        data.put("createdBy", conversation.getCreatedBy());
        data.put("createdAt", conversation.getCreatedAt());
        data.put("participantIds", members.stream().map(Participant::getUserId).toList());
        return data;
    }
}
