package com.ayush.chat.cache.memory;

import com.ayush.chat.cache.CacheStore;
import com.ayush.chat.core.connector.HealthStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-node cache with per-key TTL. Correct for one node only; use Redis for clusters.
 */
public class InMemoryCacheStore implements CacheStore {

    private record Entry(String value, long expiresAt) {
        boolean expired(long now) {
            return expiresAt > 0 && expiresAt <= now;
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final ScheduledExecutorService sweeper;
    private final AtomicBoolean closed = new AtomicBoolean();

    public InMemoryCacheStore(int maxEntries) {
        this.maxEntries = maxEntries;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "memory-cache-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleAtFixedRate(this::sweep, 10, 10, TimeUnit.SECONDS);
    }

    @Override
    public String providerId() {
        return "memory";
    }

    @Override
    public HealthStatus health() {
        return HealthStatus.up("In-process cache", Map.of("entries", entries.size(), "maxEntries", maxEntries));
    }

    @Override
    public Optional<String> get(String key) {
        Entry e = live(key);
        return e == null ? Optional.empty() : Optional.of(e.value());
    }

    @Override
    public List<String> getMany(List<String> keys) {
        List<String> values = new ArrayList<>(keys.size());
        for (String key : keys) {
            Entry e = live(key);
            values.add(e == null ? null : e.value());
        }
        return values;
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        ensureCapacity();
        entries.put(key, new Entry(value, expiry(ttl)));
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        ensureCapacity();
        long now = System.currentTimeMillis();
        boolean[] set = {false};
        entries.compute(key, (k, existing) -> {
            if (existing == null || existing.expired(now)) {
                set[0] = true;
                return new Entry(value, expiry(ttl));
            }
            return existing;
        });
        return set[0];
    }

    @Override
    public void delete(String key) {
        entries.remove(key);
    }

    @Override
    public boolean deleteIfEquals(String key, String expected) {
        boolean[] deleted = {false};
        entries.computeIfPresent(key, (k, existing) -> {
            if (existing.value().equals(expected)) {
                deleted[0] = true;
                return null;
            }
            return existing;
        });
        return deleted[0];
    }

    @Override
    public long increment(String key, long delta, Duration ttlIfNew) {
        long now = System.currentTimeMillis();
        Entry updated = entries.compute(key, (k, existing) -> {
            if (existing == null || existing.expired(now)) {
                return new Entry(String.valueOf(delta), expiry(ttlIfNew));
            }
            long next = Long.parseLong(existing.value()) + delta;
            return new Entry(String.valueOf(next), existing.expiresAt());
        });
        return Long.parseLong(updated.value());
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            sweeper.shutdownNow();
            entries.clear();
        }
    }

    private Entry live(String key) {
        Entry e = entries.get(key);
        if (e != null && e.expired(System.currentTimeMillis())) {
            entries.remove(key, e);
            return null;
        }
        return e;
    }

    private static long expiry(Duration ttl) {
        return ttl == null || ttl.isZero() || ttl.isNegative() ? 0 : System.currentTimeMillis() + ttl.toMillis();
    }

    private void ensureCapacity() {
        if (entries.size() >= maxEntries) {
            sweep();
        }
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(e -> e.getValue().expired(now));
    }
}
