package com.ayush.chat.chat;

import com.ayush.chat.auth.Principals;
import com.ayush.chat.chat.event.ChatEvent;
import com.ayush.chat.chat.event.DomainEvent;
import com.ayush.chat.core.connector.runtime.PlatformConnectors;
import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.util.Json;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Cluster-wide realtime delivery. Every event is published on the bus; each node delivers it to
 * the recipients connected locally (the STOMP broker drops it for users with no session here).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimeGateway {

    public static final String USER_QUEUE = "/queue/events";

    private final PlatformConnectors infrastructure;
    private final SimpMessagingTemplate messaging;
    private final MetricsService metrics;
    private final PlatformProperties properties;
    private final SimpUserRegistry userRegistry;

    record Delivery(String tenant, List<String> users, ChatEvent event) {
    }

    /** Cluster control messages (tenant suspended, user deactivated, cache invalidations, ...). */
    public record ControlMessage(String type, String tenant, String userId, String ref) {
        public ControlMessage(String type, String tenant, String userId) {
            this(type, tenant, userId, null);
        }
    }

    @PostConstruct
    void subscribe() {
        infrastructure.busSubscriptions().subscribe(topic("delivery"), null, this::onDelivery);
    }

    public String topic(String name) {
        return properties.getBus().getTopicPrefix() + name;
    }

    public void toUsers(String tenant, Collection<String> userIds, ChatEvent event) {
        if (userIds.isEmpty()) {
            return;
        }
        String key = event.conversationId() != null ? tenant + ":" + event.conversationId() : tenant;
        infrastructure.bus().publish(topic("delivery"), key, Json.write(new Delivery(tenant, List.copyOf(userIds), event)));
    }

    public void toUser(String tenant, String userId, ChatEvent event) {
        toUsers(tenant, List.of(userId), event);
    }

    /** Durable domain event for webhooks and push (work-queue semantics). */
    public void publishDomainEvent(DomainEvent event) {
        infrastructure.bus().publish(topic("events"), event.tenant(), Json.write(event));
    }

    public void subscribeDomainEvents(String group, Consumer<DomainEvent> handler) {
        infrastructure.busSubscriptions().subscribe(topic("events"), group,
                payload -> handler.accept(Json.read(payload, DomainEvent.class)));
    }

    public void publishControl(ControlMessage message) {
        infrastructure.bus().publish(topic("control"), message.tenant(), Json.write(message));
    }

    public void subscribeControl(Consumer<ControlMessage> handler) {
        infrastructure.busSubscriptions().subscribe(topic("control"), null,
                payload -> handler.accept(Json.read(payload, ControlMessage.class)));
    }

    /** Deliver to this node's sessions only. */
    public void deliverLocally(String tenant, String userId, ChatEvent event) {
        messaging.convertAndSendToUser(Principals.ChatUserPrincipal.key(tenant, userId), USER_QUEUE, event);
    }

    public boolean isConnectedLocally(String tenant, String userId) {
        return userRegistry.getUser(Principals.ChatUserPrincipal.key(tenant, userId)) != null;
    }

    private void onDelivery(String payload) {
        Delivery delivery = Json.read(payload, Delivery.class);
        long delivered = 0;
        for (String user : delivery.users()) {
            if (!isConnectedLocally(delivery.tenant(), user)) {
                continue;
            }
            try {
                deliverLocally(delivery.tenant(), user, delivery.event());
                delivered++;
            } catch (Exception e) {
                log.warn("Delivery to {}/{} failed: {}", delivery.tenant(), user, e.getMessage());
            }
        }
        if (delivered > 0) {
            metrics.delivered(delivered);
        }
    }
}
