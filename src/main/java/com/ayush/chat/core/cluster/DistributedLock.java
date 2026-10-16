package com.ayush.chat.core.cluster;

import com.ayush.chat.cache.CacheKeys;
import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.connector.runtime.PlatformConnectors;
import com.ayush.chat.core.exception.DomainException;
import com.ayush.chat.core.util.Ids;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Short cluster-wide mutual exclusion over the shared cache (set-if-absent with a lease,
 * compare-and-delete on release). Used for read-modify-write updates whose store operation replaces
 * a whole record, such as message reactions or membership changes.
 */
@Component
public class DistributedLock {

    private final PlatformConnectors connectors;
    private final Duration lease;
    private final Duration maxWait;

    public DistributedLock(PlatformConnectors connectors, PlatformProperties properties) {
        this.connectors = connectors;
        this.lease = properties.getLocks().getLease();
        this.maxWait = properties.getLocks().getMaxWait();
    }

    public <T> T withLock(String resource, Supplier<T> work) {
        String key = CacheKeys.lock(resource);
        String token = Ids.randomToken(16);
        long deadline = System.currentTimeMillis() + maxWait.toMillis();
        while (!connectors.cache().setIfAbsent(key, token, lease)) {
            if (System.currentTimeMillis() > deadline) {
                throw DomainException.conflict("The resource is busy, please retry");
            }
            try {
                Thread.sleep(5 + ThreadLocalRandom.current().nextInt(20));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw DomainException.unavailable("Interrupted while waiting for a lock");
            }
        }
        try {
            return work.get();
        } finally {
            connectors.cache().deleteIfEquals(key, token);
        }
    }

    public void withLock(String resource, Runnable work) {
        withLock(resource, () -> {
            work.run();
            return null;
        });
    }
}
