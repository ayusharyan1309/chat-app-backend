package com.ayush.chat.chat;

import com.ayush.chat.chat.event.DomainEvent;
import com.ayush.chat.core.connector.runtime.TenantConnectors;
import com.ayush.chat.push.PushSender;
import com.ayush.chat.store.MessageStore;
import com.ayush.chat.store.model.ChatUser;
import com.ayush.chat.tenant.Feature;
import com.ayush.chat.tenant.TenantDirectory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Sends push notifications for new messages to recipients who are offline and have not muted
 * the conversation. Consumes the event bus as a work queue (one node handles each event).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushDispatcher {

    private final RealtimeGateway gateway;
    private final TenantDirectory tenants;
    private final TenantConnectors connectors;
    private final PresenceService presence;
    private final ChatService chat;

    @PostConstruct
    void subscribe() {
        gateway.subscribeDomainEvents("push", this::onEvent);
    }

    private void onEvent(DomainEvent event) {
        if (!DomainEvent.MESSAGE_CREATED.equals(event.type()) || event.recipients() == null || event.recipients().isEmpty()) {
            return;
        }
        boolean enabled = tenants.find(event.tenant()).map(t -> t.isUsable() && t.has(Feature.PUSH_NOTIFICATIONS)).orElse(false);
        if (!enabled) {
            return;
        }
        Map<String, Boolean> online = presence.onlineMany(event.tenant(), event.recipients());
        List<String> offline = event.recipients().stream().filter(id -> !online.getOrDefault(id, false)).toList();
        if (offline.isEmpty()) {
            return;
        }
        MessageStore store = connectors.store(event.tenant());
        PushSender sender = connectors.push(event.tenant());
        @SuppressWarnings("unchecked")
        Map<String, Object> message = event.data() instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String preview = preview(message);
        String title = event.conversationTitle() != null ? event.conversationTitle()
                : event.senderName() != null ? event.senderName() : "New message";
        String body = event.conversationTitle() != null && event.senderName() != null ? event.senderName() + ": " + preview : preview;
        Map<String, String> data = Map.of(
                "type", "message.new",
                "conversationId", String.valueOf(message.getOrDefault("conversationId", "")),
                "messageId", String.valueOf(message.getOrDefault("id", "")),
                "seq", String.valueOf(message.getOrDefault("seq", "")));
        for (ChatUser user : store.findUsers(event.tenant(), offline)) {
            if (user.isDeactivated() || user.getPushTokens().isEmpty()) {
                continue;
            }
            try {
                PushSender.PushResult result = sender.send(new PushSender.PushNotification(event.tenant(), user.getId(),
                        user.getPushTokens(), title, body, data));
                if (!result.invalidTokens().isEmpty()) {
                    chat.removeDevices(event.tenant(), user.getId(), result.invalidTokens());
                }
            } catch (Exception e) {
                log.warn("Push to {}/{} failed: {}", event.tenant(), user.getId(), e.getMessage());
            }
        }
    }

    private static String preview(Map<String, Object> message) {
        Object content = message.get("content");
        if (content != null && !String.valueOf(content).isBlank()) {
            String text = String.valueOf(content);
            return text.length() > 120 ? text.substring(0, 119) + "…" : text;
        }
        return switch (String.valueOf(message.getOrDefault("type", ""))) {
            case "IMAGE" -> "📷 Photo";
            case "VIDEO" -> "🎥 Video";
            case "AUDIO" -> "🎤 Voice message";
            case "FILE" -> "📄 File";
            case "LOCATION" -> "📍 Location";
            default -> "New message";
        };
    }
}
