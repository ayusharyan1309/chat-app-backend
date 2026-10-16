package com.ayush.chat.core.config;

import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorConfig;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorRegistry;
import com.ayush.chat.core.crypto.SecretCipher;
import com.ayush.chat.core.util.Json;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layered connector configuration: bootstrap ({@code platform.connectors.*}) overridden by the
 * control-plane database (dashboard), with optional per-tenant overrides. Secrets are encrypted at
 * rest and masked on the way out. Changes made on another node are picked up by polling.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorConfigService {

    public static final String MASK = "********";

    private final ConnectorConfigRepository repository;
    private final ConnectorRegistry registry;
    private final SecretCipher cipher;
    private final PlatformProperties properties;
    private final ApplicationEventPublisher events;
    private final TaskScheduler scheduler;

    private final Map<String, Long> knownVersions = new ConcurrentHashMap<>();

    public record StoredConfig(ConnectorConfig config, long version, Instant updatedAt, String updatedBy) {
    }

    @PostConstruct
    void startPolling() {
        repository.findAll().forEach(e -> knownVersions.put(versionKey(e.getScopeKey(), e.getKind()), e.getVersion()));
        scheduler.scheduleWithFixedDelay(this::poll, properties.getConfigPollInterval());
    }

    // ------------------------------------------------------------------ reads

    public ConnectorConfig bootstrap(ConnectorKind kind) {
        PlatformProperties.ConnectorBootstrap b = properties.getConnectors().get(kind.slug());
        if (b != null && b.getProvider() != null) {
            return new ConnectorConfig(kind, b.getProvider(), b.getSettings());
        }
        return switch (kind) {
            case MESSAGE_STORE -> new ConnectorConfig(kind, "h2", Map.of("url", "jdbc:h2:file:./data/chat-store"));
            case CACHE -> new ConnectorConfig(kind, "memory", Map.of());
            case EVENT_BUS -> new ConnectorConfig(kind, "memory", Map.of());
            case BLOB_STORE -> new ConnectorConfig(kind, "local", Map.of("directory", "./data/media"));
            case PUSH -> new ConnectorConfig(kind, "none", Map.of());
        };
    }

    @Transactional(readOnly = true)
    public Optional<StoredConfig> stored(String scopeKey, ConnectorKind kind) {
        return repository.findByScopeKeyAndKind(scopeKey, kind.name()).map(this::toStored);
    }

    @Transactional(readOnly = true)
    public ConnectorConfig effectivePlatform(ConnectorKind kind) {
        return stored(ConnectorConfigChangedEvent.PLATFORM, kind).map(StoredConfig::config).orElseGet(() -> bootstrap(kind));
    }

    @Transactional(readOnly = true)
    public Optional<ConnectorConfig> tenantOverride(String tenantSlug, ConnectorKind kind) {
        return stored(ConnectorConfigChangedEvent.tenantScope(tenantSlug), kind).map(StoredConfig::config);
    }

    /** Settings with every non-empty secret replaced by {@link #MASK}. */
    public Map<String, Object> mask(ConnectorConfig config) {
        ConnectorDescriptor descriptor = registry.descriptor(config.kind(), config.provider());
        Map<String, Object> masked = new LinkedHashMap<>();
        for (ConfigField field : descriptor.fields()) {
            Object value = config.settings().get(field.key());
            if (value == null) {
                continue;
            }
            masked.put(field.key(), field.isSecret() && !String.valueOf(value).isEmpty() ? MASK : value);
        }
        return masked;
    }

    /**
     * Replace masked secret placeholders in submitted settings with the currently stored values
     * (same scope, same provider), so the UI can round-trip settings without ever seeing secrets.
     */
    public ConnectorConfig unmask(String scopeKey, ConnectorKind kind, String provider, Map<String, Object> submitted) {
        Map<String, Object> settings = new LinkedHashMap<>(submitted == null ? Map.of() : submitted);
        ConnectorConfig current = stored(scopeKey, kind).map(StoredConfig::config)
                .orElseGet(() -> ConnectorConfigChangedEvent.PLATFORM.equals(scopeKey) ? bootstrap(kind) : null);
        settings.replaceAll((key, value) -> {
            if (MASK.equals(value)) {
                return current != null && current.provider().equals(provider) ? current.settings().get(key) : null;
            }
            return value;
        });
        settings.values().removeIf(v -> v == null);
        return new ConnectorConfig(kind, provider, settings);
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public StoredConfig save(String scopeKey, ConnectorConfig config, String actor) {
        ConnectorDescriptor descriptor = registry.descriptor(config.kind(), config.provider());
        Map<String, Object> encrypted = new LinkedHashMap<>(config.settings());
        for (ConfigField field : descriptor.fields()) {
            Object value = encrypted.get(field.key());
            if (field.isSecret() && value != null) {
                encrypted.put(field.key(), cipher.encrypt(String.valueOf(value)));
            }
        }
        ConnectorConfigEntity entity = repository.findByScopeKeyAndKind(scopeKey, config.kind().name())
                .orElseGet(ConnectorConfigEntity::new);
        entity.setScopeKey(scopeKey);
        entity.setKind(config.kind().name());
        entity.setProvider(config.provider());
        entity.setSettingsJson(Json.write(encrypted));
        entity.setVersion(entity.getVersion() + 1);
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(actor);
        repository.save(entity);
        knownVersions.put(versionKey(scopeKey, config.kind().name()), entity.getVersion());
        publishAfterCommit(new ConnectorConfigChangedEvent(scopeKey, config.kind()));
        return toStored(entity);
    }

    @Transactional
    public boolean delete(String scopeKey, ConnectorKind kind) {
        Optional<ConnectorConfigEntity> existing = repository.findByScopeKeyAndKind(scopeKey, kind.name());
        existing.ifPresent(e -> {
            repository.delete(e);
            knownVersions.remove(versionKey(scopeKey, kind.name()));
            publishAfterCommit(new ConnectorConfigChangedEvent(scopeKey, kind));
        });
        return existing.isPresent();
    }

    @Transactional
    public void deleteScope(String scopeKey) {
        List<ConnectorConfigEntity> rows = repository.findByScopeKey(scopeKey);
        repository.deleteAll(rows);
        for (ConnectorConfigEntity row : rows) {
            knownVersions.remove(versionKey(scopeKey, row.getKind()));
            publishAfterCommit(new ConnectorConfigChangedEvent(scopeKey, ConnectorKind.valueOf(row.getKind())));
        }
    }

    // ------------------------------------------------------------------ internals

    private StoredConfig toStored(ConnectorConfigEntity entity) {
        Map<String, Object> settings = Optional.ofNullable(Json.readMap(entity.getSettingsJson())).orElseGet(Map::of);
        Map<String, Object> plain = new LinkedHashMap<>();
        settings.forEach((k, v) -> plain.put(k, v instanceof String s && cipher.isEncrypted(s) ? cipher.decrypt(s) : v));
        ConnectorConfig config = new ConnectorConfig(ConnectorKind.valueOf(entity.getKind()), entity.getProvider(), plain);
        return new StoredConfig(config, entity.getVersion(), entity.getUpdatedAt(), entity.getUpdatedBy());
    }

    private void publishAfterCommit(ConnectorConfigChangedEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    events.publishEvent(event);
                }
            });
        } else {
            events.publishEvent(event);
        }
    }

    /** Detect rows changed by other nodes and replay them as local events. */
    void poll() {
        try {
            Set<String> seen = new HashSet<>();
            for (ConnectorConfigEntity e : repository.findAll()) {
                String key = versionKey(e.getScopeKey(), e.getKind());
                seen.add(key);
                Long known = knownVersions.put(key, e.getVersion());
                if (known == null || known != e.getVersion()) {
                    log.info("Connector config changed elsewhere: {} {}", e.getScopeKey(), e.getKind());
                    events.publishEvent(new ConnectorConfigChangedEvent(e.getScopeKey(), ConnectorKind.valueOf(e.getKind())));
                }
            }
            for (String key : Set.copyOf(knownVersions.keySet())) {
                if (!seen.contains(key)) {
                    knownVersions.remove(key);
                    String[] parts = key.split("\\|", 2);
                    events.publishEvent(new ConnectorConfigChangedEvent(parts[0], ConnectorKind.valueOf(parts[1])));
                }
            }
        } catch (Exception e) {
            log.warn("Config poll failed: {}", e.getMessage());
        }
    }

    private static String versionKey(String scopeKey, String kind) {
        return scopeKey + "|" + kind;
    }
}
