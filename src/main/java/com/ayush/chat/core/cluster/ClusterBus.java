package com.ayush.chat.core.cluster;

import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.connector.runtime.PlatformConnectors;
import com.ayush.chat.core.util.Json;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Typed access to the platform bus: JSON payloads on {@link BusTopic}s. Subscriptions survive a
 * hot swap of the event-bus connector.
 */
@Component
@RequiredArgsConstructor
public class ClusterBus {

    private final PlatformConnectors platform;
    private final PlatformProperties properties;

    /** {@code key} selects the partition, so events with the same key stay ordered. */
    public void publish(BusTopic topic, String key, Object payload) {
        platform.bus().publish(name(topic), key, Json.write(payload));
    }

    /**
     * {@code group == null} broadcasts every message to this node; a group name shares the stream
     * among the nodes of that group (work queue).
     */
    public <T> void subscribe(BusTopic topic, String group, Class<T> type, Consumer<T> handler) {
        platform.busSubscriptions().subscribe(name(topic), group, payload -> handler.accept(Json.read(payload, type)));
    }

    private String name(BusTopic topic) {
        return topic.topicName(properties.getBus().getTopicPrefix());
    }
}
