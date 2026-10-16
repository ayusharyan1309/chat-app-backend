package com.ayush.chat.chat.push;

import com.ayush.chat.chat.application.UserDirectoryService;
import com.ayush.chat.chat.event.DomainEvent;
import com.ayush.chat.chat.port.PresenceQuery;
import com.ayush.chat.core.cluster.BusTopic;
import com.ayush.chat.core.cluster.ClusterBus;
import com.ayush.chat.core.connector.runtime.TenantConnectors;
import com.ayush.chat.push.PushSender;
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
 * Sends push notifications for new messages to recipients who are offline and have not muted the
 * conversation. Consumes the bus as a work queue (one node handles each event).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushDispatcher {

    private static final int MAX_PREVIEW = 120;

    private final ClusterBus bus;
    private final TenantDirectory tenants;
    private final TenantConnectors connectors;
    private final PresenceQuery presence;
    private final UserDirectoryService users;

    @PostConstruct
    void subscribe() {
        bus.subscribe(BusTopic.DOMAIN_EVENTS, "push", DomainEvent.class, this::onEvent);
    }

    void onEvent(DomainEvent event) {
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
        Map<?, ?> message = event.data() instanceof Map<?, ?> m ? m : Map.of();
        String preview = preview(message);
        String title = event.conversationTitle() != null ? event.conversationTitle()
                : event.senderName() != null ? event.senderName() : "New message";
        String body = event.conversationTitle() != null && event.senderName() != null ? event.senderName() + ": " + preview : preview;
        Map<String, String> data = Map.of(
                "type", "message.new",
                "conversationId", String.valueOf(value(message, "conversationId")),
                "messageId", String.valueOf(value(message, "id")),
                "seq", String.valueOf(value(message, "seq")));
        PushSender sender = connectors.push(event.tenant());
        for (ChatUser user : connectors.store(event.tenant()).findUsers(event.tenant(), offline)) {
            if (user.isDeactivated() || user.getPushTokens().isEmpty()) {
                continue;
            }
            try {
                PushSender.PushResult result = sender.send(new PushSender.PushNotification(event.tenant(), user.getId(),
                        user.getPushTokens(), title, body, data));
                if (!result.invalidTokens().isEmpty()) {
                    users.removeDevices(event.tenant(), user.getId(), result.invalidTokens());
                }
            } catch (Exception e) {
                log.warn("Push to {}/{} failed: {}", event.tenant(), user.getId(), e.getMessage());
            }
        }
    }

    private static Object value(Map<?, ?> message, String key) {
        Object v = message.get(key);
        return v == null ? "" : v;
    }

    private static String preview(Map<?, ?> message) {
        Object content = message.get("content");
        if (content != null && !String.valueOf(content).isBlank()) {
            String text = String.valueOf(content);
            return text.length() > MAX_PREVIEW ? text.substring(0, MAX_PREVIEW - 1) + "…" : text;
        }
        return switch (String.valueOf(value(message, "type"))) {
            case "IMAGE" -> "📷 Photo";
            case "VIDEO" -> "🎥 Video";
            case "AUDIO" -> "🎤 Voice message";
            case "FILE" -> "📄 File";
            case "LOCATION" -> "📍 Location";
            default -> "New message";
        };
    }
}
