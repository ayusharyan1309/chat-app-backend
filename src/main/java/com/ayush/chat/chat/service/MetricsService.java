package com.ayush.chat.chat.service;

import com.ayush.chat.cache.CacheKeys;
import com.ayush.chat.chat.dto.response.MinutePoint;
import com.ayush.chat.core.cluster.ClusterService;
import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.connector.runtime.PlatformConnectors;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Throughput metrics: Prometheus counters for scraping, plus cluster-wide per-minute and per-day
 * series in the shared cache (local deltas are flushed every few seconds) for the dashboard.
 */
@Slf4j
@Service
public class MetricsService {

    static final String MESSAGES = "msg";
    static final String DELIVERIES = "dlv";
    private static final int MAX_SERIES_MINUTES = 1440;
    private static final Duration DAILY_RETENTION = Duration.ofHours(48);

    private final PlatformConnectors platform;
    private final Duration retention;
    private final Counter messages;
    private final Counter deliveries;
    /** Deltas not yet flushed, per metric and minute. */
    private final Map<Bucket, AtomicLong> pending = new ConcurrentHashMap<>();

    private record Bucket(String metric, long epochMinute) {
        String day() {
            return LocalDate.ofInstant(Instant.ofEpochSecond(epochMinute * 60), ZoneOffset.UTC).toString();
        }
    }

    public MetricsService(PlatformConnectors platform, MeterRegistry registry, ClusterService cluster,
                          PlatformProperties properties) {
        this.platform = platform;
        this.retention = properties.getMetering().getMetricsRetention();
        this.messages = Counter.builder("chat.messages.sent").description("Messages accepted").register(registry);
        this.deliveries = Counter.builder("chat.deliveries").description("Realtime events delivered to local sessions")
                .register(registry);
        Gauge.builder("chat.connections", cluster, ClusterService::localConnectionsTotal)
                .description("Open WebSocket connections on this node").register(registry);
    }

    public void messageSent() {
        messages.increment();
        add(MESSAGES, 1);
    }

    public void delivered(long count) {
        deliveries.increment(count);
        add(DELIVERIES, count);
    }

    private void add(String metric, long delta) {
        pending.computeIfAbsent(new Bucket(metric, currentMinute()), k -> new AtomicLong()).addAndGet(delta);
    }

    @Scheduled(fixedDelayString = "#{@platformProperties.metering.metricsFlushInterval.toMillis()}")
    public void flush() {
        for (Bucket bucket : List.copyOf(pending.keySet())) {
            AtomicLong value = pending.remove(bucket);
            if (value == null || value.get() == 0) {
                continue;
            }
            try {
                platform.cache().increment(CacheKeys.minuteMetric(bucket.metric(), bucket.epochMinute()), value.get(), retention);
                platform.cache().increment(CacheKeys.dailyMetric(bucket.metric(), bucket.day()), value.get(), DAILY_RETENTION);
            } catch (Exception e) {
                pending.computeIfAbsent(bucket, k -> new AtomicLong()).addAndGet(value.get());
                log.debug("Metrics flush failed: {}", e.getMessage());
            }
        }
    }

    /** Oldest-first series for the last {@code minutes} minutes (cluster-wide). */
    public List<MinutePoint> series(int minutes) {
        int span = Math.max(1, Math.min(minutes, MAX_SERIES_MINUTES));
        long now = currentMinute();
        List<String> keys = new ArrayList<>(span * 2);
        for (long m = now - span + 1; m <= now; m++) {
            keys.add(CacheKeys.minuteMetric(MESSAGES, m));
            keys.add(CacheKeys.minuteMetric(DELIVERIES, m));
        }
        List<String> values = platform.cache().getMany(keys);
        List<MinutePoint> points = new ArrayList<>(span);
        int i = 0;
        for (long m = now - span + 1; m <= now; m++, i += 2) {
            points.add(new MinutePoint(Instant.ofEpochSecond(m * 60),
                    parse(values.get(i)) + local(new Bucket(MESSAGES, m)),
                    parse(values.get(i + 1)) + local(new Bucket(DELIVERIES, m))));
        }
        return points;
    }

    /** Messages accepted cluster-wide since midnight UTC: one cache read plus local deltas. */
    public long messagesToday() {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        long localToday = pending.entrySet().stream()
                .filter(e -> e.getKey().metric().equals(MESSAGES) && e.getKey().day().equals(today))
                .mapToLong(e -> e.getValue().get()).sum();
        return platform.cache().get(CacheKeys.dailyMetric(MESSAGES, today)).map(Long::parseLong).orElse(0L) + localToday;
    }

    private long local(Bucket bucket) {
        AtomicLong v = pending.get(bucket);
        return v == null ? 0 : v.get();
    }

    private static long currentMinute() {
        return System.currentTimeMillis() / 60_000;
    }

    private static long parse(String value) {
        return value == null ? 0 : Long.parseLong(value);
    }
}
