package com.ayush.chat.admin;

import com.ayush.chat.chat.MetricsService;
import com.ayush.chat.core.cluster.ClusterService;
import com.ayush.chat.core.config.ConnectorConfigChangedEvent;
import com.ayush.chat.core.config.ConnectorConfigService;
import com.ayush.chat.core.config.InfrastructureService;
import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.connector.ConnectorConfig;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorRegistry;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.store.model.StoreStats;
import com.ayush.chat.tenant.Feature;
import com.ayush.chat.tenant.Tenant;
import com.ayush.chat.tenant.TenantRepositories.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform-level operations: health overview, metrics, the connector catalogue and the active
 * infrastructure configuration.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class SystemController {

    private final InfrastructureService infrastructure;
    private final ConnectorRegistry registry;
    private final ConnectorConfigService configService;
    private final ClusterService cluster;
    private final MetricsService metrics;
    private final TenantRepository tenants;
    private final PlatformProperties properties;
    private final AuditService audit;

    public record ConnectorRequest(String provider, Map<String, Object> settings) {
    }

    public record KindCatalog(String kind, String displayName, String description, boolean tenantOverridable,
                              List<ConnectorDescriptor> providers) {
    }

    public record ComponentView(String kind, String displayName, String provider, String providerName, String source,
                                boolean healthy, String message, Long latencyMs, Map<String, Object> details,
                                String lastError) {
    }

    public record ConnectorState(String kind, String provider, Map<String, Object> settings, String source, Long version,
                                 Instant updatedAt, String updatedBy, HealthStatus health, String lastError) {
    }

    @GetMapping("/system/overview")
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", properties.getVersion());
        out.put("nodeId", cluster.getNodeId());
        out.put("startedAt", cluster.getStartedAt());
        out.put("uptimeSeconds", Duration.between(cluster.getStartedAt(), Instant.now()).toSeconds());
        out.put("components", Arrays.stream(ConnectorKind.values()).map(this::component).toList());
        out.put("nodes", cluster.nodes());

        List<Tenant> all = tenants.findAllVisible();
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("tenants", all.size());
        totals.put("activeTenants", all.stream().filter(Tenant::isUsable).count());
        StoreStats stats;
        try {
            stats = infrastructure.messageStore().stats(null);
        } catch (Exception e) {
            stats = new StoreStats(-1, -1, -1);
        }
        totals.put("users", stats.users());
        totals.put("conversations", stats.conversations());
        totals.put("messages", stats.messages());
        totals.put("connections", cluster.totalConnections());
        List<MetricsService.MinutePoint> series = metrics.series(60);
        long today = metrics.series(minutesSinceMidnightUtc()).stream().mapToLong(MetricsService.MinutePoint::messages).sum();
        totals.put("messagesToday", today);
        out.put("totals", totals);
        out.put("throughput", series);
        return out;
    }

    @GetMapping("/system/metrics")
    public List<MetricsService.MinutePoint> metrics(@RequestParam(defaultValue = "60") int minutes) {
        return metrics.series(minutes);
    }

    @GetMapping("/connectors")
    public List<KindCatalog> connectors() {
        return Arrays.stream(ConnectorKind.values())
                .map(k -> new KindCatalog(k.slug(), k.getDisplayName(), k.getDescription(), k.isTenantOverridable(),
                        registry.descriptors(k)))
                .toList();
    }

    @GetMapping("/features")
    public List<Map<String, Object>> features() {
        return Arrays.stream(Feature.values()).map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.getId());
            m.put("name", f.getDisplayName());
            m.put("description", f.getDescription());
            m.put("category", f.getCategory());
            m.put("status", f.getStatus());
            return m;
        }).toList();
    }

    @GetMapping("/infrastructure")
    public List<ConnectorState> infrastructure() {
        return Arrays.stream(ConnectorKind.values()).map(this::state).toList();
    }

    @GetMapping("/infrastructure/{kind}")
    public ConnectorState infrastructure(@PathVariable String kind) {
        return state(ConnectorKind.fromSlug(kind));
    }

    @PutMapping("/infrastructure/{kind}")
    public ConnectorState apply(@PathVariable String kind, @RequestBody ConnectorRequest request) {
        ConnectorKind k = ConnectorKind.fromSlug(kind);
        String previous = infrastructure.status(k).config().provider();
        infrastructure.apply(k, request.provider(), request.settings(), AuditService.currentActor());
        audit.record("infrastructure.updated", "connector", k.slug(), null, previous + " -> " + request.provider());
        return state(k);
    }

    @PostMapping("/infrastructure/{kind}/test")
    public HealthStatus test(@PathVariable String kind, @RequestBody ConnectorRequest request) {
        return infrastructure.test(ConnectorKind.fromSlug(kind), request.provider(), request.settings());
    }

    @DeleteMapping("/infrastructure/{kind}")
    public ConnectorState reset(@PathVariable String kind) {
        ConnectorKind k = ConnectorKind.fromSlug(kind);
        infrastructure.reset(k);
        audit.record("infrastructure.reset", "connector", k.slug(), null, "reverted to bootstrap");
        return state(k);
    }

    private ComponentView component(ConnectorKind kind) {
        InfrastructureService.ComponentStatus s = infrastructure.status(kind);
        String providerName = s.config().provider();
        try {
            providerName = registry.descriptor(kind, s.config().provider()).displayName();
        } catch (Exception ignored) {
            // provider no longer registered; show the raw id
        }
        return new ComponentView(kind.slug(), kind.getDisplayName(), s.config().provider(), providerName, s.source(),
                s.health().healthy(), s.health().message(), s.health().latencyMs(), s.health().details(), s.lastError());
    }

    private ConnectorState state(ConnectorKind kind) {
        InfrastructureService.ComponentStatus s = infrastructure.status(kind);
        ConnectorConfig config = s.config();
        var stored = configService.stored(ConnectorConfigChangedEvent.PLATFORM, kind);
        return new ConnectorState(kind.slug(), config.provider(), maskSafely(config), s.source(),
                stored.map(ConnectorConfigService.StoredConfig::version).orElse(null),
                stored.map(ConnectorConfigService.StoredConfig::updatedAt).orElse(null),
                stored.map(ConnectorConfigService.StoredConfig::updatedBy).orElse(null),
                s.health(), s.lastError());
    }

    private Map<String, Object> maskSafely(ConnectorConfig config) {
        try {
            return configService.mask(config);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static int minutesSinceMidnightUtc() {
        Instant now = Instant.now();
        return (int) (Duration.between(now.truncatedTo(java.time.temporal.ChronoUnit.DAYS), now).toMinutes() + 1);
    }
}
