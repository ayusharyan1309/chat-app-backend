package com.ayush.chat.cache;

import com.ayush.chat.core.connector.Connector;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Shared key-value cache used for presence, quota/rate counters and hot lookups.
 * Implementations must be safe for concurrent use from many nodes (atomic increment,
 * set-if-absent and compare-and-delete).
 */
public interface CacheStore extends Connector {

    Optional<String> get(String key);

    /** Values in the same order as keys; missing keys map to null. */
    List<String> getMany(List<String> keys);

    /** ttl null = no expiry. */
    void set(String key, String value, Duration ttl);

    /** Atomically set only if absent. Returns true when the value was set. */
    boolean setIfAbsent(String key, String value, Duration ttl);

    void delete(String key);

    /** Atomically delete only when the current value equals {@code expected}. */
    boolean deleteIfEquals(String key, String expected);

    /**
     * Atomically add {@code delta} and return the new value. When the key is created by this
     * call and {@code ttlIfNew} is non-null, the key expires after that duration.
     */
    long increment(String key, long delta, Duration ttlIfNew);
}
