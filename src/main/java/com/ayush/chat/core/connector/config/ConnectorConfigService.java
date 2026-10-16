package com.ayush.chat.core.connector.config;

import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorConfig;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorRegistry;
import com.ayush.chat.core.crypto.SecretCipher;
import com.ayush.chat.core.util.AfterCommit;
import com.ayush.chat.core.util.Json;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** (scope, kind) -> last seen version, to detect changes made on other nodes. */
    private final Map<VersionKey, Long> knownVersions = new ConcurrentHashMap<>();

    public record StoredConfig(ConnectorConfig config, long version, Instant updatedAt, String updatedBy) {
    }

    private record VersionKey(ConfigScope scope, ConnectorKind kind) {
    }

    @PostConstruct
    void startPolling() {
        repository.findAll().forEach(e -> knownVersions.put(new VersionKey(e.scope(), e.getKind()), e.getVersion()));
        scheduler.scheduleWithFixedDelay(this::poll, properties.getConfigPollInterval());
    }

    // ------------------------------------------------------------------ reads

    /** Bootstrap config from application.yml / environment, else the built-in default. */
    public ConnectorConfig bootstrap(ConnectorKind kind) {
        PlatformProperties.ConnectorBootstrap b = properties.getConnectors().get(kind.slug());
        if (b != null && b.getProvider() != null) {
            return new ConnectorConfig(kind, b.getProvider(), b.getSettings());
        }
        return DefaultConnectors.builtIn(kind);
    }

    @Transactional(readOnly = true)
    public Optional<StoredConfig> stored(ConfigScope scope, ConnectorKind kind) {
        return repository.findByScopeKeyAndKind(scope.key(), kind).map(this::toStored);
    }

    @Transactional(readOnly = true)
    public ConnectorConfig effectivePlatform(ConnectorKind kind) {
        return stored(ConfigScope.PLATFORM, kind).map(StoredConfig::config).orElseGet(() -> bootstrap(kind));
    }

    public ConfigSource platformSource(ConnectorKind kind) {
        return stored(ConfigScope.PLATFORM, kind).isPresent() ? ConfigSource.DATABASE : ConfigSource.BOOTSTRAP;
    }

    @Transactional(readOnly = true)
    public Optional<ConnectorConfig> tenantOverride(String tenantSlug, ConnectorKind kind) {
        return stored(ConfigScope.tenant(tenantSlug), kind).map(StoredConfig::config);
    }

    /** Provider id of every tenant override of one kind, in one query (tenant slug -> provider). */
    @Transactional(readOnly = true)
    public Map<String, String> tenantOverrideProviders(ConnectorKind kind) {
        Map<String, String> providers = new HashMap<>();
        for (ConnectorConfigEntity e : repository.findByKindAndScopeKeyStartingWith(kind, ConfigScope.TENANT_PREFIX)) {
            providers.put(e.scope().tenantSlug(), e.getProvider());
        }
        return providers;
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
    public ConnectorConfig unmask(ConfigScope scope, ConnectorKind kind, String provider, Map<String, Object> submitted) {
        Map<String, Object> settings = new LinkedHashMap<>(submitted == null ? Map.of() : submitted);
        ConnectorConfig current = stored(scope, kind).map(StoredConfig::config)
                .orElseGet(() -> scope.isPlatform() ? bootstrap(kind) : null);
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
    public StoredConfig save(ConfigScope scope, ConnectorConfig config, String actor) {
        ConnectorDescriptor descriptor = registry.descriptor(config.kind(), config.provider());
        Map<String, Object> encrypted = new LinkedHashMap<>(config.settings());
        for (ConfigField field : descriptor.fields()) {
            Object value = encrypted.get(field.key());
            if (field.isSecret() && value != null) {
                encrypted.put(field.key(), cipher.encrypt(String.valueOf(value)));
            }
        }
        ConnectorConfigEntity entity = repository.findByScopeKeyAndKind(scope.key(), config.kind())
                .orElseGet(ConnectorConfigEntity::new);
        entity.setScopeKey(scope.key());
        entity.setKind(config.kind());
        entity.setProvider(config.provider());
        entity.setSettingsJson(Json.write(encrypted));
        entity.setVersion(entity.getVersion() + 1);
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(actor);
        repository.save(entity);
        knownVersions.put(new VersionKey(scope, config.kind()), entity.getVersion());
        publishAfterCommit(new ConnectorConfigChangedEvent(scope, config.kind()));
        return toStored(entity);
    }

    @Transactional
    public boolean delete(ConfigScope scope, ConnectorKind kind) {
        Optional<ConnectorConfigEntity> existing = repository.findByScopeKeyAndKind(scope.key(), kind);
        existing.ifPresent(e -> {
            repository.delete(e);
            knownVersions.remove(new VersionKey(scope, kind));
            publishAfterCommit(new ConnectorConfigChangedEvent(scope, kind));
        });
        return existing.isPresent();
    }

    @Transactional
    public void deleteScope(ConfigScope scope) {
        List<ConnectorConfigEntity> rows = repository.findByScopeKey(scope.key());
        repository.deleteAll(rows);
        for (ConnectorConfigEntity row : rows) {
            knownVersions.remove(new VersionKey(scope, row.getKind()));
            publishAfterCommit(new ConnectorConfigChangedEvent(scope, row.getKind()));
        }
    }

    // ------------------------------------------------------------------ internals

    private StoredConfig toStored(ConnectorConfigEntity entity) {
        Map<String, Object> settings = Optional.ofNullable(Json.readMap(entity.getSettingsJson())).orElseGet(Map::of);
        Map<String, Object> plain = new LinkedHashMap<>();
        settings.forEach((k, v) -> plain.put(k, v instanceof String s && cipher.isEncrypted(s) ? cipher.decrypt(s) : v));
        ConnectorConfig config = new ConnectorConfig(entity.getKind(), entity.getProvider(), plain);
        return new StoredConfig(config, entity.getVersion(), entity.getUpdatedAt(), entity.getUpdatedBy());
    }

    private void publishAfterCommit(ConnectorConfigChangedEvent event) {
        AfterCommit.run(() -> events.publishEvent(event));
    }

    /** Detect rows changed by other nodes and replay them as local events. */
    void poll() {
        try {
            Map<VersionKey, Long> current = new HashMap<>();
            for (ConnectorConfigEntity e : repository.findAll()) {
                current.put(new VersionKey(e.scope(), e.getKind()), e.getVersion());
            }
            current.forEach((key, version) -> {
                Long known = knownVersions.put(key, version);
                if (known == null || !known.equals(version)) {
                    log.info("Connector config changed elsewhere: {} {}", key.scope(), key.kind());
                    events.publishEvent(new ConnectorConfigChangedEvent(key.scope(), key.kind()));
                }
            });
            for (VersionKey key : List.copyOf(knownVersions.keySet())) {
                if (!current.containsKey(key)) {
                    knownVersions.remove(key);
                    events.publishEvent(new ConnectorConfigChangedEvent(key.scope(), key.kind()));
                }
            }
        } catch (Exception e) {
            log.warn("Config poll failed: {}", e.getMessage());
        }
    }
}
