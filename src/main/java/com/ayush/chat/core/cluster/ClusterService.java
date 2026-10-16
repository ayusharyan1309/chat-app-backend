package com.ayush.chat.core.cluster;

import com.ayush.chat.cache.CacheKeys;
import com.ayush.chat.cache.CacheStore;
import com.ayush.chat.core.config.BuildVersion;
import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.runtime.PlatformConnectors;
import com.ayush.chat.core.util.Ids;
import com.ayush.chat.core.util.Json;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight cluster membership over the shared cache: each node claims a slot number and
 * heartbeats its live connection counts under {@link CacheKeys#clusterNode} with a TTL. Any node can
 * then aggregate cluster-wide connection counts per tenant without a coordinator.
 */
@Slf4j
@Service
public class ClusterService {

    private final PlatformConnectors connectors;
    private final PlatformProperties.Cluster settings;
    private final String version;
    @Getter
    private final String nodeId;
    @Getter
    private final Instant startedAt = Instant.now();
    private final Map<String, AtomicInteger> localConnections = new ConcurrentHashMap<>();
    private volatile long slot;
    private volatile List<NodeInfo> lastNodes = List.of();

    /** What each node publishes about itself. */
    record NodeHeartbeat(String nodeId, String version, Instant startedAt, Instant heartbeatAt,
                         Map<String, Integer> connections) {
    }

    public record NodeInfo(String nodeId, String version, Instant startedAt, Instant heartbeatAt,
                           Map<String, Integer> connections, int totalConnections, boolean self) {
    }

    public ClusterService(PlatformConnectors connectors, PlatformProperties properties, BuildVersion buildVersion) {
        this.connectors = connectors;
        this.settings = properties.getCluster();
        this.version = buildVersion.get();
        this.nodeId = properties.getNodeId() == null || properties.getNodeId().isBlank()
                ? "node-" + Ids.randomToken(6).toLowerCase(Locale.ROOT) : properties.getNodeId();
        connectors.holder(ConnectorKind.CACHE, CacheStore.class).onSwap((previous, next) -> {
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
            slot = connectors.cache().increment(CacheKeys.clusterSlots(), 1, null);
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

    @Scheduled(fixedDelayString = "#{@platformProperties.cluster.heartbeat.toMillis()}",
            initialDelayString = "#{@platformProperties.cluster.heartbeat.toMillis()}")
    public void heartbeat() {
        try {
            CacheStore cache = connectors.cache();
            Map<String, Integer> counts = new HashMap<>();
            localConnections.forEach((tenant, n) -> counts.put(tenant, n.get()));
            long maxSlot = cache.get(CacheKeys.clusterSlots()).map(Long::parseLong).orElse(slot);
            if (slot == 0 || maxSlot - slot > settings.getSlotWindow() / 2) {
                claimSlot();
                maxSlot = Math.max(maxSlot, slot);
            }
            cache.set(CacheKeys.clusterNode(slot),
                    Json.write(new NodeHeartbeat(nodeId, version, startedAt, Instant.now(), counts)), settings.getNodeTtl());

            List<String> keys = new ArrayList<>();
            for (long s = Math.max(1, maxSlot - settings.getSlotWindow() + 1); s <= maxSlot; s++) {
                keys.add(CacheKeys.clusterNode(s));
            }
            List<NodeInfo> nodes = new ArrayList<>();
            for (String raw : cache.getMany(keys)) {
                if (raw == null) {
                    continue;
                }
                NodeHeartbeat beat = Json.read(raw, NodeHeartbeat.class);
                Map<String, Integer> connections = beat.connections() == null ? Map.of() : beat.connections();
                nodes.add(new NodeInfo(beat.nodeId(), beat.version(), beat.startedAt(), beat.heartbeatAt(), connections,
                        connections.values().stream().mapToInt(Integer::intValue).sum(), beat.nodeId().equals(nodeId)));
            }
            lastNodes = List.copyOf(nodes);
        } catch (Exception e) {
            log.debug("Cluster heartbeat failed: {}", e.getMessage());
        }
    }
}
