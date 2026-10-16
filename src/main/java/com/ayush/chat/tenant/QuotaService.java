package com.ayush.chat.tenant;

import com.ayush.chat.cache.CacheKeys;
import com.ayush.chat.cache.CacheStore;
import com.ayush.chat.core.connector.runtime.PlatformConnectors;
import com.ayush.chat.core.error.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Real-time quota and rate-limit enforcement on shared cache counters, so limits hold
 * cluster-wide. Accepted usage is also handed to the {@link UsageMeter} for reporting.
 */
@Service
@RequiredArgsConstructor
public class QuotaService {

    /** Daily counters outlive their day so late reads around midnight still see them. */
    private static final Duration DAILY_COUNTER_TTL = Duration.ofHours(48);
    private static final Duration RATE_WINDOW_TTL = Duration.ofSeconds(3);
    private static final long BYTES_PER_MB = 1024L * 1024L;

    private final PlatformConnectors platform;
    private final UsageMeter meter;

    /**
     * Reject when the daily quota is used up. Checked before the write and counted after it
     * ({@link #countMessage}) so idempotent retries are never charged twice; concurrent senders
     * may overshoot the limit by at most the number of in-flight sends.
     */
    public void ensureMessageQuota(TenantSnapshot tenant) {
        Long limit = tenant.limits().maxMessagesPerDay();
        if (limit != null && messagesToday(tenant.slug()) >= limit) {
            throw DomainException.quotaExceeded("Daily message quota of " + limit + " reached for this tenant");
        }
    }

    public void countMessage(TenantSnapshot tenant) {
        platform.cache().increment(CacheKeys.dailyMessages(tenant.slug(), UsageMeter.today().toString()), 1, DAILY_COUNTER_TTL);
        meter.recordMessage(tenant.slug());
    }

    public long messagesToday(String tenant) {
        return platform.cache().get(CacheKeys.dailyMessages(tenant, UsageMeter.today().toString()))
                .map(Long::parseLong).orElse(0L);
    }

    /** Fixed one-second window per user. */
    public void checkSendRate(TenantSnapshot tenant, String userId) {
        Integer perSecond = tenant.limits().messagesPerSecond();
        if (perSecond == null) {
            return;
        }
        long second = System.currentTimeMillis() / 1000;
        long count = platform.cache().increment(CacheKeys.sendRate(tenant.slug(), userId, second), 1, RATE_WINDOW_TTL);
        if (count > perSecond) {
            throw DomainException.rateLimited("Slow down: at most " + perSecond + " messages per second");
        }
    }

    /** Reserve storage for an upload, or throw (reserving nothing) when it would exceed the plan. */
    public void consumeStorage(TenantSnapshot tenant, long bytes) {
        CacheStore cache = platform.cache();
        String key = CacheKeys.storageBytes(tenant.slug());
        if (cache.get(key).isEmpty()) {
            // Seed from the metered total, e.g. after a cache flush or on first use.
            cache.setIfAbsent(key, String.valueOf(meter.storedBytes(tenant.slug())), null);
        }
        long used = cache.increment(key, bytes, null);
        Long limitMb = tenant.limits().maxStorageMb();
        if (limitMb != null && used > limitMb * BYTES_PER_MB) {
            cache.increment(key, -bytes, null);
            throw DomainException.quotaExceeded("Media storage quota of " + limitMb + " MB reached for this tenant");
        }
        meter.recordMediaBytes(tenant.slug(), bytes);
    }
}
