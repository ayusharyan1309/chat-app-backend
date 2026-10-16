package com.ayush.chat.core.cluster;

/**
 * The platform's internal bus topics. Names are prefixed with {@code platform.bus.topic-prefix} so
 * several deployments can share one broker.
 */
public enum BusTopic {
    /** Realtime events, fanned out to every node (each delivers to its local sessions). */
    DELIVERY("delivery"),
    /** Durable domain events, consumed as a work queue by webhooks and push. */
    DOMAIN_EVENTS("events"),
    /** Cache invalidations and forced disconnects. */
    CONTROL("control"),
    /** Online/offline transitions for presence watchers. */
    PRESENCE("presence");

    private final String name;

    BusTopic(String name) {
        this.name = name;
    }

    public String topicName(String prefix) {
        return prefix + name;
    }
}
