package com.ayush.chat.bus.memory;

import com.ayush.chat.bus.EventBus;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.error.InfrastructureUnavailableException;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * In-process bus. Per-key ordering is kept by routing each key to one of N single-threaded lanes.
 * Using the bus after {@link #close()} is an {@link InfrastructureUnavailableException} (503).
 */
@Slf4j
public class InMemoryEventBus implements EventBus {

    private static final String COMPONENT = ConnectorKind.EVENT_BUS.getDisplayName();

    private record Sub(String group, Consumer<String> handler) {
    }

    private final Map<String, List<Sub>> topics = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> groupCursors = new ConcurrentHashMap<>();
    private final ExecutorService[] lanes;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong published = new AtomicLong();

    public InMemoryEventBus(int lanes) {
        this.lanes = new ExecutorService[Math.max(1, lanes)];
        for (int i = 0; i < this.lanes.length; i++) {
            int lane = i;
            this.lanes[i] = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "memory-bus-" + lane);
                t.setDaemon(true);
                return t;
            });
        }
    }

    @Override
    public String providerId() {
        return "memory";
    }

    @Override
    public HealthStatus health() {
        return closed.get() ? HealthStatus.down("closed")
                : HealthStatus.up("In-process bus", Map.of("topics", topics.size(), "published", published.get()));
    }

    @Override
    public void publish(String topic, String key, String payload) {
        ensureOpen();
        published.incrementAndGet();
        List<Sub> subs = topics.getOrDefault(topic, List.of());
        if (subs.isEmpty()) {
            return;
        }
        List<Sub> targets = new ArrayList<>();
        Map<String, List<Sub>> byGroup = new HashMap<>();
        for (Sub sub : subs) {
            if (sub.group() == null) {
                targets.add(sub);
            } else {
                byGroup.computeIfAbsent(sub.group(), g -> new ArrayList<>()).add(sub);
            }
        }
        byGroup.forEach((group, members) -> {
            int index = Math.floorMod(groupCursors.computeIfAbsent(topic + "/" + group, g -> new AtomicInteger())
                    .getAndIncrement(), members.size());
            targets.add(members.get(index));
        });
        ExecutorService lane = lanes[Math.floorMod(key == null ? 0 : key.hashCode(), lanes.length)];
        for (Sub sub : targets) {
            try {
                lane.execute(() -> {
                    try {
                        sub.handler().accept(payload);
                    } catch (Exception e) {
                        log.error("Event handler failed on topic {}", topic, e);
                    }
                });
            } catch (RejectedExecutionException e) {
                throw new InfrastructureUnavailableException(COMPONENT, e); // closed concurrently
            }
        }
    }

    @Override
    public Subscription subscribe(String topic, String group, Consumer<String> handler) {
        ensureOpen();
        Sub sub = new Sub(group, handler);
        topics.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(sub);
        return () -> topics.getOrDefault(topic, List.of()).remove(sub);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (ExecutorService lane : lanes) {
                lane.shutdown();
            }
            topics.clear();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new InfrastructureUnavailableException(COMPONENT, "closed");
        }
    }
}
