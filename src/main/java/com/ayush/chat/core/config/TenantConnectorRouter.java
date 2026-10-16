package com.ayush.chat.core.config;

import com.ayush.chat.core.connector.Connector;
import com.ayush.chat.core.connector.ConnectorConfig;
import com.ayush.chat.core.connector.ConnectorHolder;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorRegistry;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.error.ApiException;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Resolves the connector for a tenant: a dedicated one when the tenant has an override,
 * otherwise the platform default. Override lookups are cached and invalidated by config events.
 */
@Slf4j
public class TenantConnectorRouter<T extends Connector> implements AutoCloseable {

    private final ConnectorKind kind;
    private final ConnectorRegistry registry;
    private final ConnectorConfigService configService;
    private final Supplier<T> platformDefault;
    private final Map<String, Optional<ConnectorHolder<T>>> tenants = new ConcurrentHashMap<>();

    public TenantConnectorRouter(ConnectorKind kind, ConnectorRegistry registry, ConnectorConfigService configService,
                                 Supplier<T> platformDefault) {
        this.kind = kind;
        this.registry = registry;
        this.configService = configService;
        this.platformDefault = platformDefault;
    }

    public T forTenant(String tenantSlug) {
        Optional<ConnectorHolder<T>> dedicated = tenants.computeIfAbsent(tenantSlug, this::load);
        return dedicated.map(ConnectorHolder::get).orElseGet(platformDefault);
    }

    public boolean isDedicated(String tenantSlug) {
        return tenants.computeIfAbsent(tenantSlug, this::load).isPresent();
    }

    private Optional<ConnectorHolder<T>> load(String tenantSlug) {
        Optional<ConnectorConfig> override = configService.tenantOverride(tenantSlug, kind);
        if (override.isEmpty()) {
            return Optional.empty();
        }
        ConnectorHolder<T> holder = new ConnectorHolder<>(kind.getDisplayName() + "[" + tenantSlug + "]", registry, 30_000);
        try {
            holder.apply(override.get());
        } catch (Exception e) {
            log.error("Dedicated {} for tenant {} failed: {}", kind.getDisplayName(), tenantSlug, e.getMessage());
            throw ApiException.unavailable("Tenant " + kind.getDisplayName().toLowerCase() + " is unavailable: " + e.getMessage());
        }
        return Optional.of(holder);
    }

    /** Apply a new override (connects first) and persist it. */
    public void apply(String tenantSlug, ConnectorConfig config, String actor) {
        Optional<ConnectorHolder<T>> existing = tenants.get(tenantSlug);
        if (existing != null && existing.isPresent()) {
            existing.get().apply(config);
        } else {
            ConnectorHolder<T> holder = new ConnectorHolder<>(kind.getDisplayName() + "[" + tenantSlug + "]", registry, 30_000);
            holder.apply(config);
            tenants.put(tenantSlug, Optional.of(holder));
        }
        configService.save(ConnectorConfigChangedEvent.tenantScope(tenantSlug), config, actor);
    }

    public void invalidate(String tenantSlug) {
        Optional<ConnectorHolder<T>> removed = tenants.remove(tenantSlug);
        if (removed != null) {
            removed.ifPresent(ConnectorHolder::close);
        }
    }

    public HealthStatus health(String tenantSlug) {
        try {
            return forTenant(tenantSlug).health();
        } catch (Exception e) {
            return HealthStatus.down(e.getMessage());
        }
    }

    /** Re-load the override after a config change; keeps the live holder when the config is unchanged. */
    public void onConfigChanged(String tenantSlug) {
        Optional<ConnectorConfig> override = configService.tenantOverride(tenantSlug, kind);
        Optional<ConnectorHolder<T>> current = tenants.get(tenantSlug);
        if (override.isEmpty()) {
            invalidate(tenantSlug);
            return;
        }
        if (current != null && current.isPresent()) {
            try {
                current.get().apply(override.get());
            } catch (Exception e) {
                log.error("Re-applying {} for tenant {} failed: {}", kind.getDisplayName(), tenantSlug, e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        tenants.values().forEach(h -> h.ifPresent(ConnectorHolder::close));
        tenants.clear();
    }
}
