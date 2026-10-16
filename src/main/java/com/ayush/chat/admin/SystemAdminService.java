package com.ayush.chat.admin;

import com.ayush.chat.admin.SystemViews.ComponentView;
import com.ayush.chat.admin.SystemViews.ConnectorState;
import com.ayush.chat.admin.SystemViews.SystemOverview;
import com.ayush.chat.admin.SystemViews.Totals;
import com.ayush.chat.audit.AuditAction;
import com.ayush.chat.audit.AuditService;
import com.ayush.chat.chat.metrics.MetricsService;
import com.ayush.chat.core.cluster.ClusterService;
import com.ayush.chat.core.config.BuildVersion;
import com.ayush.chat.core.connector.ConnectorConfig;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorRegistry;
import com.ayush.chat.core.connector.config.ConfigScope;
import com.ayush.chat.core.connector.config.ConnectorConfigService;
import com.ayush.chat.core.connector.runtime.PlatformConnectors;
import com.ayush.chat.store.model.StoreStats;
import com.ayush.chat.tenant.Tenant;
import com.ayush.chat.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Platform overview and the platform-wide connector configuration. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemAdminService {

    private static final int OVERVIEW_MINUTES = 60;
    private static final StoreStats UNAVAILABLE = new StoreStats(-1, -1, -1);

    private final PlatformConnectors platform;
    private final ConnectorRegistry registry;
    private final ConnectorConfigService configService;
    private final ClusterService cluster;
    private final MetricsService metrics;
    private final TenantRepository tenants;
    private final BuildVersion buildVersion;
    private final AuditService audit;

    public SystemOverview overview() {
        List<Tenant> all = tenants.findAllVisible();
        StoreStats stats;
        try {
            stats = platform.messageStore().stats(null);
        } catch (RuntimeException e) {
            log.debug("Platform store stats unavailable: {}", e.getMessage());
            stats = UNAVAILABLE;
        }
        Totals totals = new Totals(all.size(), all.stream().filter(Tenant::isUsable).count(), stats.users(),
                stats.conversations(), stats.messages(), cluster.totalConnections(), metrics.messagesToday());
        return new SystemOverview(buildVersion.get(), cluster.getNodeId(), cluster.getStartedAt(),
                Duration.between(cluster.getStartedAt(), Instant.now()).toSeconds(),
                Arrays.stream(ConnectorKind.values()).map(this::component).toList(), cluster.nodes(), totals,
                metrics.series(OVERVIEW_MINUTES));
    }

    public List<ConnectorState> states() {
        return Arrays.stream(ConnectorKind.values()).map(this::state).toList();
    }

    public ConnectorState state(ConnectorKind kind) {
        PlatformConnectors.ComponentStatus s = platform.status(kind);
        ConnectorConfig config = s.config();
        var stored = configService.stored(ConfigScope.PLATFORM, kind);
        return new ConnectorState(kind.slug(), config.provider(), maskSafely(config), s.source(),
                stored.map(ConnectorConfigService.StoredConfig::version).orElse(null),
                stored.map(ConnectorConfigService.StoredConfig::updatedAt).orElse(null),
                stored.map(ConnectorConfigService.StoredConfig::updatedBy).orElse(null),
                s.health(), s.lastError());
    }

    /** Hot-swaps a platform connector on every node. */
    public ConnectorState apply(ConnectorKind kind, String provider, Map<String, Object> settings) {
        String previous = platform.status(kind).config().provider();
        platform.apply(kind, provider, settings, AuditService.currentActor());
        audit.record(AuditAction.PLATFORM_CONNECTOR_UPDATED, kind.slug(), null, previous + " -> " + provider);
        return state(kind);
    }

    /** Back to the bootstrap configuration from application.yml / environment. */
    public ConnectorState reset(ConnectorKind kind) {
        platform.reset(kind);
        audit.record(AuditAction.PLATFORM_CONNECTOR_RESET, kind.slug(), null, "reverted to bootstrap");
        return state(kind);
    }

    private ComponentView component(ConnectorKind kind) {
        PlatformConnectors.ComponentStatus s = platform.status(kind);
        String provider = s.config().provider();
        String providerName = registry.descriptors(kind).stream().filter(d -> d.id().equals(provider)).findFirst()
                .map(d -> d.displayName()).orElse(provider);
        return new ComponentView(kind.slug(), kind.getDisplayName(), provider, providerName, s.source(),
                s.health().healthy(), s.health().message(), s.health().latencyMs(), s.health().details(), s.lastError());
    }

    private Map<String, Object> maskSafely(ConnectorConfig config) {
        try {
            return configService.mask(config);
        } catch (RuntimeException e) {
            // e.g. the provider is no longer registered: show nothing rather than leak a secret.
            return Map.of();
        }
    }
}
