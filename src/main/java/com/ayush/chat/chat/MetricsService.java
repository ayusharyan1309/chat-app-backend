package com.ayush.chat.chat;

import com.ayush.chat.core.cluster.ClusterService;
import com.ayush.chat.core.connector.runtime.PlatformConnectors;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Throughput metrics. Prometheus counters for scraping, plus cluster-wide per-minute series kept
 * in the shared cache (local deltas are flushed every few seconds) for the dashboard.
 */
@Slf4j
@Service
public class MetricsService {

    private static final Duration RETENTION = Duration.ofHours(25);

    private final PlatformConnectors infrastructure;
    private final Counter messages;
    private final Counter deliveries;
    private final Map<String, AtomicLong> pending = new ConcurrentHashMap<>();

    public record MinutePoint(Instant minute, long messages, long deliveries) {
    }

    public MetricsService(PlatformConnectors infrastructure, MeterRegistry registry, ClusterService cluster) {
        this.infrastructure = infrastructure;
        this.messages = Counter.builder("chat.messages.sent").description("Messages accepted").register(registry);
        this.deliveries = Counter.builder("chat.deliveries").description("Realtime events delivered to local sessions").register(registry);
        Gauge.builder("chat.connections", cluster, ClusterService::localConnectionsTotal)
                .description("Open WebSocket connections on this node").register(registry);
    }

    public void messageSent() {
        messages.increment();
        add("msg", 1);
    }

    public void delivered(long count) {
        deliveries.increment(count);
        add("dlv", count);
    }

    private void add(String metric, long delta) {
        long minute = System.currentTimeMillis() / 60_000;
        pending.compute(metric + ":" + minute, (k, v) -> {
            AtomicLong target = v == null ? new AtomicLong() : v;
            target.addAndGet(delta);
            return target;
        });
    }

    @Scheduled(fixedDelay = 5_000)
    public void flush() {
        for (String key : List.copyOf(pending.keySet())) {
            AtomicLong value = pending.remove(key);
            if (value == null || value.get() == 0) {
                continue;
            }
            try {
                infrastructure.cache().increment("metrics:" + key, value.get(), RETENTION);
            } catch (Exception e) {
                pending.compute(key, (k, v) -> {
                    AtomicLong target = v == null ? new AtomicLong() : v;
                    target.addAndGet(value.get());
                    return target;
                });
                log.debug("Metrics flush failed: {}", e.getMessage());
            }
        }
    }

    /** Oldest-first series for the last {@code minutes} minutes (cluster-wide). */
    public List<MinutePoint> series(int minutes) {
        int span = Math.max(1, Math.min(minutes, 1440));
        long now = System.currentTimeMillis() / 60_000;
        List<String> keys = new ArrayList<>(span * 2);
        for (long m = now - span + 1; m <= now; m++) {
            keys.add("metrics:msg:" + m);
            keys.add("metrics:dlv:" + m);
        }
        List<String> values = infrastructure.cache().getMany(keys);
        List<MinutePoint> points = new ArrayList<>(span);
        int i = 0;
        for (long m = now - span + 1; m <= now; m++, i += 2) {
            long msg = parse(values.get(i)) + local("msg:" + m);
            long dlv = parse(values.get(i + 1)) + local("dlv:" + m);
            points.add(new MinutePoint(Instant.ofEpochSecond(m * 60), msg, dlv));
        }
        return points;
    }

    private long local(String key) {
        AtomicLong v = pending.get(key);
        return v == null ? 0 : v.get();
    }

    private static long parse(String value) {
        return value == null ? 0 : Long.parseLong(value);
    }
}
