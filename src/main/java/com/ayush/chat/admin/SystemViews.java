package com.ayush.chat.admin;

import com.ayush.chat.chat.metrics.MetricsService;
import com.ayush.chat.core.cluster.ClusterService;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.connector.config.ConfigSource;
import com.ayush.chat.tenant.Feature;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Dashboard representations of platform-wide state. */
public final class SystemViews {

    private SystemViews() {
    }

    public record SystemOverview(String version, String nodeId, Instant startedAt, long uptimeSeconds,
                                 List<ComponentView> components, List<ClusterService.NodeInfo> nodes, Totals totals,
                                 List<MetricsService.MinutePoint> throughput) {
    }

    /** {@code -1} store counts mean the platform store could not be reached. */
    public record Totals(long tenants, long activeTenants, long users, long conversations, long messages, int connections,
                         long messagesToday) {
    }

    public record ComponentView(String kind, String displayName, String provider, String providerName, ConfigSource source,
                                boolean healthy, String message, Long latencyMs, Map<String, Object> details,
                                String lastError) {
    }

    public record ConnectorRequest(String provider, Map<String, Object> settings) {
    }

    public record KindCatalog(String kind, String displayName, String description, boolean tenantOverridable,
                              List<ConnectorDescriptor> providers) {
    }

    public record ConnectorState(String kind, String provider, Map<String, Object> settings, ConfigSource source, Long version,
                                 Instant updatedAt, String updatedBy, HealthStatus health, String lastError) {
    }

    public record FeatureView(String id, String name, String description, String category, Feature.Status status) {
        static FeatureView of(Feature f) {
            return new FeatureView(f.getId(), f.getDisplayName(), f.getDescription(), f.getCategory(), f.getStatus());
        }
    }
}
