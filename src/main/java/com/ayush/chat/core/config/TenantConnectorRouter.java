package com.ayush.chat.core.config;

import com.ayush.chat.core.connector.Connector;
import com.ayush.chat.core.connector.ConnectorConfig;
import com.ayush.chat.core.connector.ConnectorHolder;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorRegistry;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.error.ApiException;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Resolves the connector for a tenant: a dedicated one when the tenant has an override,
 * otherwise the platform default.
 *
 * <ul>
 *   <li>Resolutions (including "no override") are cached and dropped on every config change, so a
 *       newly assigned dedicated store is picked up by every node, not only the one that saved it.</li>
 *   <li>Connections are opened outside any map lock; a failed connection is remembered briefly so an
 *       unreachable tenant database cannot pile up blocked requests on a node.</li>
 * </ul>
 */
@Slf4j
public class TenantConnectorRouter<T extends Connector> implements AutoCloseable {

    private static final Duration FAILURE_BACKOFF = Duration.ofSeconds(5);

    private final ConnectorKind kind;
    private final ConnectorRegistry registry;
    private final ConnectorConfigService configService;
    private final Supplier<T> platformDefault;
    private final Map<String, Optional<ConnectorHolder<T>>> tenants = new ConcurrentHashMap<>();
    private final Map<String, Failure> failures = new ConcurrentHashMap<>();
    /** ReentrantLock rather than synchronized: blocking connects must not pin virtual threads. */
    private final Map<String, ReentrantLock> loadLocks = new ConcurrentHashMap<>();

    private record Failure(String message, Instant until) {
    }

    public TenantConnectorRouter(ConnectorKind kind, ConnectorRegistry registry, ConnectorConfigService configService,
                                 Supplier<T> platformDefault) {
        this.kind = kind;
        this.registry = registry;
        this.configService = configService;
        this.platformDefault = platformDefault;
    }

    public T forTenant(String tenantSlug) {
        Optional<ConnectorHolder<T>> resolved = tenants.get(tenantSlug);
        if (resolved == null) {
            resolved = load(tenantSlug);
        }
        return resolved.map(ConnectorHolder::get).orElseGet(platformDefault);
    }

    public boolean isDedicated(String tenantSlug) {
        return configService.tenantOverride(tenantSlug, kind).isPresent();
    }

    /** One loader per tenant; other tenants are never blocked by a slow connection. */
    private Optional<ConnectorHolder<T>> load(String tenantSlug) {
        Failure failure = failures.get(tenantSlug);
        if (failure != null && Instant.now().isBefore(failure.until())) {
            throw ApiException.unavailable(failure.message());
        }
        ReentrantLock lock = loadLocks.computeIfAbsent(tenantSlug, s -> new ReentrantLock());
        lock.lock();
        try {
            Optional<ConnectorHolder<T>> existing = tenants.get(tenantSlug);
            if (existing != null) {
                return existing;
            }
            Optional<ConnectorConfig> override = configService.tenantOverride(tenantSlug, kind);
            if (override.isEmpty()) {
                tenants.put(tenantSlug, Optional.empty());
                return Optional.empty();
            }
            ConnectorHolder<T> holder = newHolder(tenantSlug);
            try {
                holder.apply(override.get());
            } catch (Exception e) {
                String message = "Tenant " + kind.getDisplayName().toLowerCase() + " is unavailable: " + e.getMessage();
                log.error("Dedicated {} for tenant {} failed: {}", kind.getDisplayName(), tenantSlug, e.getMessage());
                failures.put(tenantSlug, new Failure(message, Instant.now().plus(FAILURE_BACKOFF)));
                throw ApiException.unavailable(message);
            }
            failures.remove(tenantSlug);
            Optional<ConnectorHolder<T>> loaded = Optional.of(holder);
            tenants.put(tenantSlug, loaded);
            return loaded;
        } finally {
            lock.unlock();
        }
    }

    private ConnectorHolder<T> newHolder(String tenantSlug) {
        return new ConnectorHolder<>(kind.getDisplayName() + "[" + tenantSlug + "]", registry, 30_000);
    }

    /** Apply a new override (connects first, outside the routing map) and persist it. */
    public void apply(String tenantSlug, ConnectorConfig config, String actor) {
        Optional<ConnectorHolder<T>> existing = tenants.get(tenantSlug);
        if (existing != null && existing.isPresent()) {
            existing.get().apply(config);
        } else {
            ConnectorHolder<T> holder = newHolder(tenantSlug);
            holder.apply(config);
            tenants.put(tenantSlug, Optional.of(holder));
        }
        failures.remove(tenantSlug);
        configService.save(ConnectorConfigChangedEvent.tenantScope(tenantSlug), config, actor);
    }

    public void invalidate(String tenantSlug) {
        failures.remove(tenantSlug);
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

    /**
     * React to a config change (local or from another node): swap a live dedicated connector in
     * place, or drop the cached resolution so the next request re-resolves it.
     */
    public void onConfigChanged(String tenantSlug) {
        failures.remove(tenantSlug);
        Optional<ConnectorConfig> override = configService.tenantOverride(tenantSlug, kind);
        Optional<ConnectorHolder<T>> current = tenants.get(tenantSlug);
        if (override.isPresent() && current != null && current.isPresent()) {
            try {
                current.get().apply(override.get());
                return;
            } catch (Exception e) {
                log.error("Re-applying {} for tenant {} failed: {}", kind.getDisplayName(), tenantSlug, e.getMessage());
            }
        }
        invalidate(tenantSlug);
    }

    @Override
    public void close() {
        tenants.values().forEach(h -> h.ifPresent(ConnectorHolder::close));
        tenants.clear();
    }
}
