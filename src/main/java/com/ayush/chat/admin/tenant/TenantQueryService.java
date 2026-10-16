package com.ayush.chat.admin.tenant;

import com.ayush.chat.admin.tenant.TenantViews.FeatureState;
import com.ayush.chat.admin.tenant.TenantViews.Stats;
import com.ayush.chat.admin.tenant.TenantViews.TenantDetail;
import com.ayush.chat.admin.tenant.TenantViews.TenantSummary;
import com.ayush.chat.core.cluster.ClusterService;
import com.ayush.chat.core.connector.ConnectorConfig;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.config.ConnectorConfigService;
import com.ayush.chat.core.connector.runtime.TenantConnectors;
import com.ayush.chat.store.model.StoreStats;
import com.ayush.chat.tenant.Feature;
import com.ayush.chat.tenant.Plan;
import com.ayush.chat.tenant.PlanRepository;
import com.ayush.chat.tenant.QuotaService;
import com.ayush.chat.tenant.Tenant;
import com.ayush.chat.tenant.TenantDirectory;
import com.ayush.chat.tenant.TenantService;
import com.ayush.chat.tenant.TenantSnapshot;
import com.ayush.chat.tenant.UsageMeter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read models for the dashboard's tenant pages. Each view loads what it needs once (plans, override
 * providers) instead of per row, and degrades gracefully when a tenant's store is unreachable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantQueryService {

    private static final StoreStats UNAVAILABLE = new StoreStats(-1, -1, -1);

    private final TenantService tenants;
    private final TenantDirectory directory;
    private final PlanRepository plans;
    private final ConnectorConfigService connectorConfigs;
    private final TenantConnectors connectors;
    private final QuotaService quotas;
    private final UsageMeter usage;
    private final ClusterService cluster;

    @Transactional(readOnly = true)
    public List<TenantSummary> list(String query, Tenant.Status status) {
        Map<String, String> planNames = plans.findAll().stream()
                .collect(Collectors.toMap(Plan::getCode, Plan::getName, (a, b) -> a));
        Map<String, String> storeProviders = connectorConfigs.tenantOverrideProviders(ConnectorKind.MESSAGE_STORE);
        return tenants.list(query, status).stream()
                .map(t -> new TenantSummary(t.getSlug(), t.getName(), t.getContactEmail(), t.getDomain(), t.getStatus(),
                        t.getPlanCode(), planNames.getOrDefault(t.getPlanCode(), t.getPlanCode()),
                        storeProviders.get(t.getSlug()), messagesToday(t.getSlug()), cluster.connections(t.getSlug()),
                        t.getCreatedAt(), t.getTrialEndsAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public TenantDetail detail(String slug) {
        Tenant t = tenants.get(slug);
        TenantSnapshot snapshot = directory.snapshot(t, plans.findByCode(t.getPlanCode()).orElse(null));
        StoreStats stats = storeStats(slug);
        long today = messagesToday(slug);
        int connections = cluster.connections(slug);
        String storeProvider = connectorConfigs.tenantOverride(slug, ConnectorKind.MESSAGE_STORE)
                .map(ConnectorConfig::provider).orElse(null);
        return new TenantDetail(t.getSlug(), t.getName(), t.getContactEmail(), t.getDomain(), t.getStatus(), t.getPlanCode(),
                snapshot.planName(), storeProvider, today, connections, t.getCreatedAt(), t.getTrialEndsAt(), t.getUpdatedAt(),
                snapshot.branding(), snapshot.settings(), snapshot.limits(), t.limitOverridesOrNone(), features(t, snapshot),
                new Stats(stats.users(), stats.conversations(), stats.messages(), today, connections, usage.storedBytes(slug)));
    }

    @Transactional(readOnly = true)
    public List<FeatureState> features(String slug) {
        Tenant t = tenants.get(slug);
        return features(t, directory.snapshot(t, plans.findByCode(t.getPlanCode()).orElse(null)));
    }

    private List<FeatureState> features(Tenant tenant, TenantSnapshot snapshot) {
        return Arrays.stream(Feature.values())
                .map(f -> new FeatureState(f.getId(), f.getDisplayName(), f.getCategory(), f.getStatus(),
                        snapshot.has(f) && f.getStatus() != Feature.Status.PLANNED, tenants.featureSource(f, tenant)))
                .toList();
    }

    private StoreStats storeStats(String slug) {
        try {
            return connectors.store(slug).stats(slug);
        } catch (RuntimeException e) {
            log.debug("Store stats unavailable for {}: {}", slug, e.getMessage());
            return UNAVAILABLE;
        }
    }

    private long messagesToday(String slug) {
        try {
            return quotas.messagesToday(slug);
        } catch (RuntimeException e) {
            log.debug("Message counter unavailable for {}: {}", slug, e.getMessage());
            return 0;
        }
    }
}
