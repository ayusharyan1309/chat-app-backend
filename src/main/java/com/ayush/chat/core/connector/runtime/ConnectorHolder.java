package com.ayush.chat.core.connector.runtime;

import com.ayush.chat.core.connector.Connector;
import com.ayush.chat.core.connector.ConnectorConfig;
import com.ayush.chat.core.connector.ConnectorRegistry;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

/**
 * Holds the active connector for a slot and hot-swaps it when configuration changes.
 * In-flight callers keep using the previous instance, which is closed after a grace period.
 */
@Slf4j
public class ConnectorHolder<T extends Connector> implements AutoCloseable {

    private final String name;
    private final ConnectorRegistry registry;
    private final Duration retireGrace;
    private final AtomicReference<Active<T>> active = new AtomicReference<>();
    private final List<BiConsumer<T, T>> swapListeners = new CopyOnWriteArrayList<>();
    /** Not synchronized: connecting blocks, and must not pin virtual threads. */
    private final ReentrantLock lock = new ReentrantLock();

    private record Active<T>(ConnectorConfig config, T connector) {
    }

    public ConnectorHolder(String name, ConnectorRegistry registry, Duration retireGrace) {
        this.name = name;
        this.registry = registry;
        this.retireGrace = retireGrace;
    }

    /** Listener receives (previous, next); previous is null on first activation. */
    public void onSwap(BiConsumer<T, T> listener) {
        swapListeners.add(listener);
    }

    public T get() {
        Active<T> current = active.get();
        if (current == null) {
            throw new IllegalStateException(name + " is not configured");
        }
        return current.connector();
    }

    public boolean isActive() {
        return active.get() != null;
    }

    public ConnectorConfig config() {
        Active<T> current = active.get();
        return current == null ? null : current.config();
    }

    /**
     * Activate the given config. No-op if it equals the active one. On failure the previous
     * connector stays active and the exception propagates.
     */
    public boolean apply(ConnectorConfig config) {
        lock.lock();
        try {
            return doApply(config);
        } finally {
            lock.unlock();
        }
    }

    private boolean doApply(ConnectorConfig config) {
        Active<T> current = active.get();
        if (current != null && Objects.equals(current.config(), config)) {
            return false;
        }
        T next = registry.create(config);
        active.set(new Active<>(config, next));
        log.info("{} -> {}", name, config.provider());
        T previous = current == null ? null : current.connector();
        for (BiConsumer<T, T> listener : swapListeners) {
            try {
                listener.accept(previous, next);
            } catch (Exception e) {
                log.error("{} swap listener failed", name, e);
            }
        }
        if (previous != null) {
            registry.retire(previous, retireGrace);
        }
        return true;
    }

    @Override
    public void close() {
        lock.lock();
        try {
            Active<T> current = active.getAndSet(null);
            if (current != null) {
                current.connector().close();
            }
        } finally {
            lock.unlock();
        }
    }
}
