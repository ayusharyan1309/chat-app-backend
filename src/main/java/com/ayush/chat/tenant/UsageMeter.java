package com.ayush.chat.tenant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Usage metering for billing and analytics. Each node accumulates deltas in memory and flushes
 * them to the control plane periodically, so the database never sees per-message writes.
 * Enforcement does not depend on this (see {@link QuotaService}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageMeter {

    private static final int MESSAGES = 0;
    private static final int MEDIA_BYTES = 1;
    private static final int API_CALLS = 2;
    private static final int MAX_HISTORY_DAYS = 366;

    private final UsageRepository repository;

    /** (tenant, day) -> counters not yet flushed. */
    private final Map<Bucket, AtomicLongArray> pending = new ConcurrentHashMap<>();

    public record DailyUsage(String day, long messages, long mediaBytes, long apiCalls) {
    }

    private record Bucket(String tenant, LocalDate day) {
    }

    public void recordMessage(String tenant) {
        record(tenant, MESSAGES, 1);
    }

    public void recordMediaBytes(String tenant, long bytes) {
        record(tenant, MEDIA_BYTES, bytes);
    }

    public void recordApiCall(String tenant) {
        record(tenant, API_CALLS, 1);
    }

    /** Atomic per bucket with {@link #flush()}'s remove(), so no increment lands in an already-flushed array. */
    private void record(String tenant, int metric, long delta) {
        pending.compute(new Bucket(tenant, today()), (k, counters) -> {
            AtomicLongArray target = counters == null ? new AtomicLongArray(3) : counters;
            target.addAndGet(metric, delta);
            return target;
        });
    }

    @Scheduled(fixedDelayString = "#{@platformProperties.metering.usageFlushInterval.toMillis()}",
            initialDelayString = "#{@platformProperties.metering.usageFlushInterval.toMillis()}")
    public void flush() {
        for (Bucket bucket : List.copyOf(pending.keySet())) {
            AtomicLongArray counters = pending.remove(bucket);
            if (counters == null) {
                continue;
            }
            try {
                repository.add(bucket.tenant(), bucket.day(), counters.get(MESSAGES), counters.get(MEDIA_BYTES),
                        counters.get(API_CALLS));
            } catch (Exception e) {
                log.warn("Usage flush failed for {} on {}: {} (will retry)", bucket.tenant(), bucket.day(), e.getMessage());
                pending.merge(bucket, counters, (current, failed) -> {
                    for (int i = 0; i < 3; i++) {
                        current.addAndGet(i, failed.get(i));
                    }
                    return current;
                });
            }
        }
    }

    /** Zero-filled daily usage, oldest first, including this node's not-yet-flushed counts. */
    public List<DailyUsage> history(String tenant, int days) {
        int span = Math.max(1, Math.min(days, MAX_HISTORY_DAYS));
        LocalDate end = today();
        LocalDate start = end.minusDays(span - 1L);
        Map<String, long[]> rows = new LinkedHashMap<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            rows.put(d.toString(), new long[3]);
        }
        repository.accumulate(tenant, start, rows);
        pending.forEach((bucket, counters) -> {
            long[] v = rows.get(bucket.day().toString());
            if (bucket.tenant().equals(tenant) && v != null) {
                v[0] += counters.get(MESSAGES);
                v[1] += counters.get(MEDIA_BYTES);
                v[2] += counters.get(API_CALLS);
            }
        });
        List<DailyUsage> out = new ArrayList<>(rows.size());
        rows.forEach((day, v) -> out.add(new DailyUsage(day, v[0], v[1], v[2])));
        return out;
    }

    /** All media bytes ever uploaded by the tenant (stored plus pending). */
    public long storedBytes(String tenant) {
        long local = pending.entrySet().stream().filter(e -> e.getKey().tenant().equals(tenant))
                .mapToLong(e -> e.getValue().get(MEDIA_BYTES)).sum();
        return repository.totalMediaBytes(tenant) + local;
    }

    static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
