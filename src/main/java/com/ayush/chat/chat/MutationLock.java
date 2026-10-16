package com.ayush.chat.chat;

import com.ayush.chat.core.config.InfrastructureService;
import com.ayush.chat.core.error.ApiException;
import com.ayush.chat.core.util.Ids;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Short cluster-wide lock over the shared cache, for read-modify-write updates whose store
 * operation replaces the whole record (message edits/reactions/deletes, membership changes).
 * Without it two concurrent reactions lose one, or a reaction can resurrect a deleted message.
 */
@Component
@RequiredArgsConstructor
public class MutationLock {

    private static final Duration LEASE = Duration.ofSeconds(10);
    private static final long WAIT_MILLIS = 3_000;

    private final InfrastructureService infrastructure;

    public <T> T withLock(String key, Supplier<T> work) {
        String lockKey = "lock:" + key;
        String token = Ids.randomToken(16);
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (!infrastructure.cache().setIfAbsent(lockKey, token, LEASE)) {
            if (System.currentTimeMillis() > deadline) {
                throw ApiException.conflict("The resource is busy, please retry");
            }
            try {
                Thread.sleep(5 + ThreadLocalRandom.current().nextInt(20));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApiException.unavailable("Interrupted while waiting for a lock");
            }
        }
        try {
            return work.get();
        } finally {
            infrastructure.cache().deleteIfEquals(lockKey, token);
        }
    }
}
