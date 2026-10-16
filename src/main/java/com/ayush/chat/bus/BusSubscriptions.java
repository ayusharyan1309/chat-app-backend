package com.ayush.chat.bus;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Subscriptions that survive event-bus hot swaps: when the bus provider changes, every
 * registered subscription is re-created on the new bus.
 */
@Slf4j
public class BusSubscriptions {

    private final Supplier<EventBus> bus;
    private final List<Registration> registrations = new CopyOnWriteArrayList<>();

    public BusSubscriptions(Supplier<EventBus> bus) {
        this.bus = bus;
    }

    private final class Registration {
        final String topic;
        final String group;
        final Consumer<String> handler;
        volatile EventBus.Subscription current;

        Registration(String topic, String group, Consumer<String> handler) {
            this.topic = topic;
            this.group = group;
            this.handler = handler;
        }

        void attach(EventBus target) {
            current = target.subscribe(topic, group, handler);
        }
    }

    public void subscribe(String topic, String group, Consumer<String> handler) {
        Registration registration = new Registration(topic, group, handler);
        registrations.add(registration);
        registration.attach(bus.get());
    }

    /** Called when the bus has been swapped; the old bus is closed separately. */
    public void reattach(EventBus next) {
        for (Registration r : registrations) {
            try {
                if (r.current != null) {
                    r.current.close();
                }
            } catch (Exception e) {
                log.debug("Closing old subscription failed: {}", e.getMessage());
            }
            r.attach(next);
        }
        log.info("Re-attached {} bus subscriptions to {}", registrations.size(), next.providerId());
    }
}
