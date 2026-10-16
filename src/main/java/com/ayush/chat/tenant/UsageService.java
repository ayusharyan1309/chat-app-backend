package com.ayush.chat.tenant;

import com.ayush.chat.cache.CacheStore;
import com.ayush.chat.core.config.InfrastructureService;
import com.ayush.chat.core.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Quotas and usage metering.
 *
 * <p>Enforcement uses shared cache counters (cluster-wide, real time). Metering accumulates
 * per-node deltas that are flushed to {@code platform_usage_daily} every 30 s, so the control
 * plane database never sees per-message writes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageService {

    private static final int MESSAGES = 0;
    private static final int MEDIA_BYTES = 1;
    private static final int API_CALLS = 2;

    private final InfrastructureService infrastructure;
    private final JdbcTemplate jdbc;

    private final Map<String, AtomicLongArray> pending = new ConcurrentHashMap<>();

    public record DailyUsage(String day, long messages, long mediaBytes, long apiCalls) {
    }

    // ------------------------------------------------------------------ enforcement

    /**
     * Reject when the daily quota is used up. Checked before the write and counted after it
     * ({@link #countMessage}) so idempotent retries are never charged twice; concurrent senders
     * may overshoot the limit by at most the number of in-flight sends.
     */
    public void ensureMessageQuota(TenantSnapshot tenant) {
        Long limit = tenant.limits().maxMessagesPerDay();
        if (limit != null && messagesToday(tenant.slug()) >= limit) {
            throw ApiException.quotaExceeded("Daily message quota of " + limit + " reached for this tenant");
        }
    }

    public void countMessage(TenantSnapshot tenant) {
        infrastructure.cache().increment("quota:msg:" + tenant.slug() + ":" + today(), 1, Duration.ofHours(48));
        record(tenant.slug(), MESSAGES, 1);
    }

    public long messagesToday(String slug) {
        return infrastructure.cache().get("quota:msg:" + slug + ":" + today()).map(Long::parseLong).orElse(0L);
    }

    /** Fixed one-second window per user. */
    public void checkRate(TenantSnapshot tenant, String userId) {
        Integer perSecond = tenant.limits().messagesPerSecond();
        if (perSecond == null) {
            return;
        }
        long second = System.currentTimeMillis() / 1000;
        long count = infrastructure.cache().increment("rate:" + tenant.slug() + ":" + userId + ":" + second, 1,
                Duration.ofSeconds(3));
        if (count > perSecond) {
            throw ApiException.rateLimited("Slow down: at most " + perSecond + " messages per second");
        }
    }

    /** Reserve storage for an upload; returns false (and reserves nothing) when it would exceed the plan. */
    public void consumeStorage(TenantSnapshot tenant, long bytes) {
        CacheStore cache = infrastructure.cache();
        String key = "quota:storage:" + tenant.slug();
        if (cache.get(key).isEmpty()) {
            cache.setIfAbsent(key, String.valueOf(storedBytes(tenant.slug())), null);
        }
        long used = cache.increment(key, bytes, null);
        Long limitMb = tenant.limits().maxStorageMb();
        if (limitMb != null && used > limitMb * 1024 * 1024) {
            cache.increment(key, -bytes, null);
            throw ApiException.quotaExceeded("Media storage quota of " + limitMb + " MB reached for this tenant");
        }
        record(tenant.slug(), MEDIA_BYTES, bytes);
    }

    public void recordApiCall(String slug) {
        record(slug, API_CALLS, 1);
    }

    // ------------------------------------------------------------------ metering

    private void record(String slug, int metric, long delta) {
        pending.computeIfAbsent(slug + "|" + today(), k -> new AtomicLongArray(3)).addAndGet(metric, delta);
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void flush() {
        for (String key : List.copyOf(pending.keySet())) {
            AtomicLongArray counters = pending.remove(key);
            if (counters == null) {
                continue;
            }
            String[] parts = key.split("\\|");
            long messages = counters.get(MESSAGES);
            long media = counters.get(MEDIA_BYTES);
            long api = counters.get(API_CALLS);
            try {
                Date day = Date.valueOf(LocalDate.parse(parts[1]));
                int updated = jdbc.update("UPDATE platform_usage_daily SET messages = messages + ?, media_bytes = media_bytes + ?,"
                        + " api_calls = api_calls + ? WHERE tenant_slug = ? AND usage_day = ?", messages, media, api, parts[0], day);
                if (updated == 0) {
                    try {
                        jdbc.update("INSERT INTO platform_usage_daily (tenant_slug, usage_day, messages, media_bytes, api_calls)"
                                + " VALUES (?, ?, ?, ?, ?)", parts[0], day, messages, media, api);
                    } catch (org.springframework.dao.DuplicateKeyException race) {
                        jdbc.update("UPDATE platform_usage_daily SET messages = messages + ?, media_bytes = media_bytes + ?,"
                                + " api_calls = api_calls + ? WHERE tenant_slug = ? AND usage_day = ?", messages, media, api, parts[0], day);
                    }
                }
            } catch (Exception e) {
                log.warn("Usage flush failed for {}: {} (will retry)", key, e.getMessage());
                pending.computeIfAbsent(key, k -> new AtomicLongArray(3));
                AtomicLongArray retry = pending.get(key);
                retry.addAndGet(MESSAGES, messages);
                retry.addAndGet(MEDIA_BYTES, media);
                retry.addAndGet(API_CALLS, api);
            }
        }
    }

    /** Zero-filled daily usage, oldest first, including not-yet-flushed local counts. */
    public List<DailyUsage> history(String slug, int days) {
        int span = Math.max(1, Math.min(days, 366));
        LocalDate end = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = end.minusDays(span - 1L);
        Map<String, long[]> rows = new LinkedHashMap<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            rows.put(d.toString(), new long[3]);
        }
        jdbc.query("SELECT usage_day, messages, media_bytes, api_calls FROM platform_usage_daily"
                        + " WHERE tenant_slug = ? AND usage_day >= ?", rs -> {
                    long[] v = rows.get(rs.getDate(1).toLocalDate().toString());
                    if (v != null) {
                        v[0] += rs.getLong(2);
                        v[1] += rs.getLong(3);
                        v[2] += rs.getLong(4);
                    }
                }, slug, Date.valueOf(start));
        pending.forEach((key, counters) -> {
            String[] parts = key.split("\\|");
            long[] v = rows.get(parts[1]);
            if (parts[0].equals(slug) && v != null) {
                v[0] += counters.get(MESSAGES);
                v[1] += counters.get(MEDIA_BYTES);
                v[2] += counters.get(API_CALLS);
            }
        });
        List<DailyUsage> out = new ArrayList<>();
        rows.forEach((day, v) -> out.add(new DailyUsage(day, v[0], v[1], v[2])));
        return out;
    }

    public long storedBytes(String slug) {
        Long total = jdbc.queryForObject("SELECT COALESCE(SUM(media_bytes), 0) FROM platform_usage_daily WHERE tenant_slug = ?",
                Long.class, slug);
        long local = pending.entrySet().stream().filter(e -> e.getKey().startsWith(slug + "|"))
                .mapToLong(e -> e.getValue().get(MEDIA_BYTES)).sum();
        return (total == null ? 0 : total) + local;
    }

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }
}
