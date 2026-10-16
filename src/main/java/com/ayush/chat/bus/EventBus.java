package com.ayush.chat.bus;

import com.ayush.chat.core.connector.Connector;

import java.util.function.Consumer;

/**
 * Cluster-wide message transport.
 *
 * <ul>
 *   <li><b>Broadcast</b> ({@code group == null}): every subscribed node receives every message.
 *       Used to fan deliveries out to whichever node holds a recipient's socket.</li>
 *   <li><b>Work queue</b> ({@code group != null}): each message is handled by exactly one
 *       subscriber of the group across the cluster (at-least-once). Used for webhooks/push.</li>
 * </ul>
 * Messages with the same {@code key} are delivered in publish order (per-key ordering).
 * Handlers are invoked off the publisher's thread and must not block for long.
 */
public interface EventBus extends Connector {

    void publish(String topic, String key, String payload);

    Subscription subscribe(String topic, String group, Consumer<String> handler);

    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
