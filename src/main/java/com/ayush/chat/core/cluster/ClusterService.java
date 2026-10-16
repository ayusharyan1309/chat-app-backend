package com.ayush.chat.core.cluster;

import com.ayush.chat.cache.CacheStore;
import com.ayush.chat.core.config.InfrastructureService;
import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.util.Ids;
import com.ayush.chat.core.util.Json;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight cluster membership over the shared cache: each node claims a slot number and
 * heartbeats its live connection counts under {@code cluster:node:<slot>} with a TTL. Any node can
 * then aggregate cluster-wide connection counts per tenant without a coordinator.
 */
@Slf4j
@Service
public class ClusterService {

    private static final Duration NODE_TTL = Duration.ofSeconds(30);
    private static final int SLOT_WINDOW = 256;

    private final InfrastructureService infrastructure;
    private final PlatformProperties properties;
    @Getter
    private final String nodeId;
    @Getter
    private final Instant startedAt = Instant.now();
    private final Map<String, AtomicInteger> localConnections = new ConcurrentHashMap<>();
    private volatile long slot;
    private volatile List<NodeInfo> lastNodes = List.of();

    public record NodeInfo(String nodeId, String version, Instant startedAt, Instant heartbeatAt,
                           Map<String, Integer> connections, int totalConnections, boolean self) {
    }

    public ClusterService(InfrastructureService infrastructure, PlatformProperties properties) {
        this.infrastructure = infrastructure;
        this.properties = properties;
        this.nodeId = properties.getNodeId() == null || properties.getNodeId().isBlank()
                ? "node-" + Ids.randomToken(6).toLowerCase() : properties.getNodeId();
        infrastructure.holder(ConnectorKind.CACHE, CacheStore.class).onSwap((previous, next) -> {
            if (previous != null) {
                claimSlot();
            }
        });
    }

    @PostConstruct
    void start() {
        claimSlot();
        heartbeat();
        log.info("Node {} joined the cluster (slot {})", nodeId, slot);
    }

    private void claimSlot() {
        try {
            slot = infrastructure.cache().increment("cluster:slots", 1, null);
        } catch (Exception e) {
            log.warn("Could not claim cluster slot: {}", e.getMessage());
        }
    }

    public void connectionOpened(String tenant) {
        localConnections.computeIfAbsent(tenant, t -> new AtomicInteger()).incrementAndGet();
    }

    public void connectionClosed(String tenant) {
        localConnections.computeIfPresent(tenant, (t, n) -> n.decrementAndGet() <= 0 ? null : n);
    }

    public int localConnections(String tenant) {
        AtomicInteger n = localConnections.get(tenant);
        return n == null ? 0 : n.get();
    }

    public int localConnectionsTotal() {
        return localConnections.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    /** Cluster-wide connections for a tenant: other nodes as of their last heartbeat plus this node live. */
    public int connections(String tenant) {
        int others = lastNodes.stream().filter(n -> !n.self())
                .mapToInt(n -> n.connections().getOrDefault(tenant, 0)).sum();
        return others + localConnections(tenant);
    }

    public int totalConnections() {
        return lastNodes.stream().filter(n -> !n.self()).mapToInt(NodeInfo::totalConnections).sum()
                + localConnectionsTotal();
    }

    public List<NodeInfo> nodes() {
        return lastNodes;
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    public void heartbeat() {
        try {
            CacheStore cache = infrastructure.cache();
            Map<String, Integer> counts = new HashMap<>();
            localConnections.forEach((tenant, n) -> counts.put(tenant, n.get()));
            Map<String, Object> beat = Map.of("nodeId", nodeId, "version", properties.getVersion(),
                    "startedAt", startedAt.toString(), "heartbeatAt", Instant.now().toString(), "connections", counts);
            long maxSlot = cache.get("cluster:slots").map(Long::parseLong).orElse(slot);
            if (slot == 0 || maxSlot - slot > SLOT_WINDOW / 2) {
                claimSlot();
                maxSlot = Math.max(maxSlot, slot);
            }
            cache.set("cluster:node:" + slot, Json.write(beat), NODE_TTL);

            List<String> keys = new ArrayList<>();
            for (long s = Math.max(1, maxSlot - SLOT_WINDOW + 1); s <= maxSlot; s++) {
                keys.add("cluster:node:" + s);
            }
            List<NodeInfo> nodes = new ArrayList<>();
            for (String raw : cache.getMany(keys)) {
                if (raw == null) {
                    continue;
                }
                Map<String, Object> m = Json.readMap(raw);
                @SuppressWarnings("unchecked")
                Map<String, Object> conns = (Map<String, Object>) m.getOrDefault("connections", Map.of());
                Map<String, Integer> typed = new HashMap<>();
                conns.forEach((k, v) -> typed.put(k, ((Number) v).intValue()));
                String id = String.valueOf(m.get("nodeId"));
                nodes.add(new NodeInfo(id, String.valueOf(m.get("version")), Instant.parse(String.valueOf(m.get("startedAt"))),
                        Instant.parse(String.valueOf(m.get("heartbeatAt"))), typed,
                        typed.values().stream().mapToInt(Integer::intValue).sum(), id.equals(nodeId)));
            }
            lastNodes = List.copyOf(nodes);
        } catch (Exception e) {
            log.debug("Cluster heartbeat failed: {}", e.getMessage());
        }
    }
}
