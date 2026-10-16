package com.ayush.chat.core.connector;

import com.ayush.chat.core.exception.DomainException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Discovers every {@link ConnectorFactory} bean and builds connectors from {@link ConnectorConfig}s.
 */
@Slf4j
@Component
public class ConnectorRegistry {

    /** Closes swapped-out connectors after their grace period; owned here so it shuts down with the context. */
    private final ScheduledExecutorService retirer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "connector-retirer");
        t.setDaemon(true);
        return t;
    });

    private final Map<ConnectorKind, Map<String, ConnectorFactory<?>>> factories = new EnumMap<>(ConnectorKind.class);

    public ConnectorRegistry(List<ConnectorFactory<?>> discovered) {
        for (ConnectorKind kind : ConnectorKind.values()) {
            factories.put(kind, new LinkedHashMap<>());
        }
        discovered.stream()
                .sorted(Comparator.comparing(ConnectorFactory::id))
                .forEach(f -> {
                    ConnectorFactory<?> previous = factories.get(f.kind()).put(f.id(), f);
                    if (previous != null) {
                        throw new IllegalStateException("Duplicate connector " + f.kind() + "/" + f.id());
                    }
                });
        factories.forEach((kind, map) -> log.info("Connectors for {}: {}", kind, map.keySet()));
    }

    public List<ConnectorDescriptor> descriptors(ConnectorKind kind) {
        return factories.get(kind).values().stream().map(ConnectorFactory::descriptor).toList();
    }

    public ConnectorDescriptor descriptor(ConnectorKind kind, String provider) {
        return factory(kind, provider).descriptor();
    }

    public ConnectorFactory<?> factory(ConnectorKind kind, String provider) {
        ConnectorFactory<?> factory = factories.get(kind).get(provider);
        if (factory == null) {
            throw DomainException.badRequest("Unknown " + kind.getDisplayName() + " provider '" + provider
                    + "'. Available: " + factories.get(kind).keySet());
        }
        return factory;
    }

    /** Validate required fields; returns human-readable problems (empty when valid). */
    public List<String> validate(ConnectorConfig config) {
        ConnectorDescriptor descriptor = descriptor(config.kind(), config.provider());
        ConnectorSettings settings = ConnectorSettings.withDefaults(descriptor, config.settings());
        List<String> problems = new ArrayList<>();
        for (ConfigField field : descriptor.fields()) {
            if (field.required() && !settings.has(field.key())) {
                problems.add(field.label() + " is required");
            }
        }
        return problems;
    }

    @SuppressWarnings("unchecked")
    public <T extends Connector> T create(ConnectorConfig config) {
        List<String> problems = validate(config);
        if (!problems.isEmpty()) {
            throw DomainException.badRequest(String.join("; ", problems));
        }
        ConnectorFactory<T> factory = (ConnectorFactory<T>) factory(config.kind(), config.provider());
        ConnectorSettings settings = ConnectorSettings.withDefaults(factory.descriptor(), config.settings());
        try {
            return factory.create(settings);
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            throw DomainException.badRequest("Could not connect to " + factory.descriptor().displayName()
                    + ": " + rootMessage(e));
        }
    }

    /** Create a throw-away connector, check its health, and close it. */
    public HealthStatus test(ConnectorConfig config) {
        long start = System.nanoTime();
        Connector connector = null;
        try {
            connector = create(config);
            return connector.health().withLatency((System.nanoTime() - start) / 1_000_000);
        } catch (Exception e) {
            return HealthStatus.down(e.getMessage()).withLatency((System.nanoTime() - start) / 1_000_000);
        } finally {
            if (connector != null) {
                connector.close();
            }
        }
    }

    /** Close a connector that was swapped out, once in-flight callers have had time to finish. */
    public void retire(Connector connector, Duration grace) {
        retirer.schedule(connector::close, grace.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        retirer.shutdownNow();
    }

    public static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
        return root == e ? message : e.getMessage() + " (" + message + ")";
    }
}
