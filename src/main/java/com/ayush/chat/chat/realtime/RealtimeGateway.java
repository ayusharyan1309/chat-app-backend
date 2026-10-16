package com.ayush.chat.chat.realtime;

import com.ayush.chat.core.cluster.BusTopic;
import com.ayush.chat.core.cluster.ClusterBus;
import com.ayush.chat.auth.Principals;
import com.ayush.chat.chat.event.ChatEvent;
import com.ayush.chat.chat.event.ControlMessage;
import com.ayush.chat.chat.event.DomainEvent;
import com.ayush.chat.chat.metrics.MetricsService;
import com.ayush.chat.chat.port.RealtimePublisher;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * Cluster-wide realtime delivery. Every event is published on the bus; each node delivers it to
 * the recipients connected locally (the STOMP broker drops it for users with no session here).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimeGateway implements RealtimePublisher {

    public static final String USER_QUEUE = "/queue/events";

    private final ClusterBus bus;
    private final SimpMessagingTemplate messaging;
    private final SimpUserRegistry userRegistry;
    private final MetricsService metrics;

    record Delivery(String tenant, List<String> users, ChatEvent event) {
    }

    @PostConstruct
    void subscribe() {
        bus.subscribe(BusTopic.DELIVERY, null, Delivery.class, this::onDelivery);
    }

    @Override
    public void toUsers(String tenant, Collection<String> userIds, ChatEvent event) {
        if (userIds.isEmpty()) {
            return;
        }
        // Keyed by conversation so one conversation's events stay in order on partitioned buses.
        String key = event.conversationId() != null ? tenant + ":" + event.conversationId() : tenant;
        bus.publish(BusTopic.DELIVERY, key, new Delivery(tenant, List.copyOf(userIds), event));
    }

    @Override
    public void publishDomainEvent(DomainEvent event) {
        bus.publish(BusTopic.DOMAIN_EVENTS, event.tenant(), event);
    }

    @Override
    public void publishControl(ControlMessage message) {
        bus.publish(BusTopic.CONTROL, message.tenant(), message);
    }

    /** Deliver to this node's sessions only. */
    public void deliverLocally(String tenant, String userId, ChatEvent event) {
        messaging.convertAndSendToUser(Principals.ChatUserPrincipal.key(tenant, userId), USER_QUEUE, event);
    }

    public boolean isConnectedLocally(String tenant, String userId) {
        return userRegistry.getUser(Principals.ChatUserPrincipal.key(tenant, userId)) != null;
    }

    private void onDelivery(Delivery delivery) {
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
