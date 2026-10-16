package com.ayush.chat.chat.port;

import com.ayush.chat.chat.event.ChatEvent;
import com.ayush.chat.chat.event.ControlMessage;
import com.ayush.chat.chat.event.DomainEvent;

import java.util.Collection;
import java.util.List;

/**
 * Outbound port of the messaging domain: everything the application services publish. The
 * production adapter routes over the configured event bus; tests substitute a recorder.
 */
public interface RealtimePublisher {

    /** Deliver a realtime event to every session of these users, on whichever node they are connected. */
    void toUsers(String tenant, Collection<String> userIds, ChatEvent event);

    default void toUser(String tenant, String userId, ChatEvent event) {
        toUsers(tenant, List.of(userId), event);
    }

    /** Durable domain event for webhooks and push (work-queue semantics). */
    void publishDomainEvent(DomainEvent event);

    void publishControl(ControlMessage message);
}
