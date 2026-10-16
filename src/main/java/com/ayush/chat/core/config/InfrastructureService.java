package com.ayush.chat.core.config;

import com.ayush.chat.blob.BlobStore;
import com.ayush.chat.bus.EventBus;
import com.ayush.chat.cache.CacheStore;
import com.ayush.chat.core.connector.Connector;
import com.ayush.chat.core.connector.ConnectorConfig;
import com.ayush.chat.core.connector.ConnectorHolder;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorRegistry;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.error.ApiException;
import com.ayush.chat.push.PushSender;
import com.ayush.chat.store.MessageStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the platform-level connector for every slot and hot-swaps it when its configuration
 * changes. Cache, bus, media and push fall back to safe local providers if the configured one is
 * unreachable at startup; the message store never falls back silently (writing to the wrong
 * database would be worse than failing).
 */
@Slf4j
@Service
public class InfrastructureService implements DisposableBean {

    private final ConnectorRegistry registry;
    private final ConnectorConfigService configService;
    private final Map<ConnectorKind, ConnectorHolder<? extends Connector>> holders = new EnumMap<>(ConnectorKind.class);
    private final Map<ConnectorKind, String> lastErrors = new ConcurrentHashMap<>();
    private final BusSubscriptions busSubscriptions;

    public InfrastructureService(ConnectorRegistry registry, ConnectorConfigService configService) {
        this.registry = registry;
        this.configService = configService;
        for (ConnectorKind kind : ConnectorKind.values()) {
            holders.put(kind, new ConnectorHolder<>(kind.getDisplayName(), registry, 30_000));
        }
        this.busSubscriptions = new BusSubscriptions(this::bus);
        holder(ConnectorKind.EVENT_BUS, EventBus.class).onSwap((previous, next) -> {
            if (previous != null) {
                busSubscriptions.reattach(next);
            }
        });
    }

    public record ComponentStatus(ConnectorKind kind, ConnectorConfig config, String source, HealthStatus health,
                                  String lastError) {
    }

    @PostConstruct
    void activateAll() {
        for (ConnectorKind kind : ConnectorKind.values()) {
            activate(kind);
        }
    }

    private void activate(ConnectorKind kind) {
        ConnectorConfig config = configService.effectivePlatform(kind);
        try {
            holders.get(kind).apply(config);
            lastErrors.remove(kind);
        } catch (Exception e) {
            String message = e.getMessage();
            lastErrors.put(kind, message);
            log.error("{} '{}' failed to start: {}", kind.getDisplayName(), config.provider(), message);
            if (!holders.get(kind).isActive()) {
                fallback(kind).ifPresent(fb -> {
                    log.warn("{} falling back to '{}' until the configured provider is reachable",
                            kind.getDisplayName(), fb.provider());
                    holders.get(kind).apply(fb);
                });
            }
        }
    }

    private Optional<ConnectorConfig> fallback(ConnectorKind kind) {
        return switch (kind) {
            case MESSAGE_STORE -> Optional.empty();
            case CACHE -> Optional.of(new ConnectorConfig(kind, "memory", Map.of()));
            case EVENT_BUS -> Optional.of(new ConnectorConfig(kind, "memory", Map.of()));
            case BLOB_STORE -> Optional.of(new ConnectorConfig(kind, "local", Map.of("directory", "./data/media")));
            case PUSH -> Optional.of(new ConnectorConfig(kind, "none", Map.of()));
        };
    }

    @EventListener
    public void onConfigChanged(ConnectorConfigChangedEvent event) {
        if (event.isPlatform()) {
            activate(event.kind());
        }
    }

    // ------------------------------------------------------------------ accessors

    @SuppressWarnings("unchecked")
    public <T extends Connector> ConnectorHolder<T> holder(ConnectorKind kind, Class<T> type) {
        return (ConnectorHolder<T>) holders.get(kind);
    }

    private <T extends Connector> T active(ConnectorKind kind, Class<T> type) {
        ConnectorHolder<T> holder = holder(kind, type);
        if (!holder.isActive()) {
            throw ApiException.unavailable(kind.getDisplayName() + " is unavailable: "
                    + lastErrors.getOrDefault(kind, "not configured"));
        }
        return holder.get();
    }

    public MessageStore messageStore() {
        return active(ConnectorKind.MESSAGE_STORE, MessageStore.class);
    }

    public CacheStore cache() {
        return active(ConnectorKind.CACHE, CacheStore.class);
    }

    public EventBus bus() {
        return active(ConnectorKind.EVENT_BUS, EventBus.class);
    }

    public BlobStore blobs() {
        return active(ConnectorKind.BLOB_STORE, BlobStore.class);
    }

    public PushSender push() {
        return active(ConnectorKind.PUSH, PushSender.class);
    }

    public BusSubscriptions busSubscriptions() {
        return busSubscriptions;
    }

    // ------------------------------------------------------------------ admin operations

    /**
     * Connect with the new settings first; only persist when the connection works, so a typo can
     * never take the platform down.
     */
    public ComponentStatus apply(ConnectorKind kind, String provider, Map<String, Object> settings, String actor) {
        ConnectorConfig config = configService.unmask(ConnectorConfigChangedEvent.PLATFORM, kind, provider, settings);
        ConnectorHolder<? extends Connector> holder = holders.get(kind);
        ConnectorConfig previous = holder.config();
        holder.apply(config);
        try {
            configService.save(ConnectorConfigChangedEvent.PLATFORM, config, actor);
        } catch (RuntimeException e) {
            if (previous != null) {
                holder.apply(previous);
            }
            throw e;
        }
        lastErrors.remove(kind);
        return status(kind);
    }

    public HealthStatus test(ConnectorKind kind, String provider, Map<String, Object> settings) {
        return registry.test(configService.unmask(ConnectorConfigChangedEvent.PLATFORM, kind, provider, settings));
    }

    public ComponentStatus reset(ConnectorKind kind) {
        configService.delete(ConnectorConfigChangedEvent.PLATFORM, kind);
        activate(kind);
        return status(kind);
    }

    public ComponentStatus status(ConnectorKind kind) {
        ConnectorHolder<? extends Connector> holder = holders.get(kind);
        String source = configService.stored(ConnectorConfigChangedEvent.PLATFORM, kind).isPresent() ? "database" : "bootstrap";
        if (!holder.isActive()) {
            return new ComponentStatus(kind, configService.effectivePlatform(kind), source,
                    HealthStatus.down(lastErrors.getOrDefault(kind, "not configured")), lastErrors.get(kind));
        }
        long start = System.nanoTime();
        HealthStatus health;
        try {
            health = holder.get().health();
        } catch (Exception e) {
            health = HealthStatus.down(e.getMessage());
        }
        return new ComponentStatus(kind, holder.config(), source,
                health.withLatency((System.nanoTime() - start) / 1_000_000), lastErrors.get(kind));
    }

    @Override
    public void destroy() {
        holders.values().forEach(ConnectorHolder::close);
    }
}
