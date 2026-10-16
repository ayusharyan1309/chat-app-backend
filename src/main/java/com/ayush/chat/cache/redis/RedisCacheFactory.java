package com.ayush.chat.cache.redis;

import com.ayush.chat.cache.CacheStore;
import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorFactory;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.core.connector.Maturity;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** GA: the cache conformance suite runs in CI against a live Redis service container. */
@Component
public class RedisCacheFactory implements ConnectorFactory<CacheStore> {

    private static final int DEFAULT_TIMEOUT_MS = 3000;

    private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(ConnectorKind.CACHE,
            "redis", "Redis",
            "Shared cache for multi-node deployments (presence, counters, rate limits, locks).",
            "Key-value", Maturity.GA,
            List.of("Valkey", "KeyDB", "Dragonfly", "Amazon ElastiCache", "Azure Cache for Redis", "Upstash"),
            List.of(ConfigField.of("uri", "Connection URI").secret().required().defaultValue("redis://localhost:6379")
                            .placeholder("rediss://:password@host:6380/0")
                            .description("redis:// or rediss:// (TLS); password, user and database index go in the URI. "
                                    + "redis-sentinel:// is also accepted").build(),
                    ConfigField.of("keyPrefix", "Key prefix").defaultValue("chat:")
                            .description("Prepended to every key so one Redis can be shared").build(),
                    ConfigField.of("timeoutMs", "Timeout (ms)").integer(DEFAULT_TIMEOUT_MS)
                            .description("Connect and command timeout").build()));

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    /** Settings arrive with the descriptor's defaults applied ({@link ConnectorSettings#withDefaults}). */
    @Override
    public CacheStore create(ConnectorSettings settings) {
        int timeoutMs = settings.integer("timeoutMs", DEFAULT_TIMEOUT_MS);
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        return new RedisCacheStore(settings.required("uri"), settings.string("keyPrefix"), Duration.ofMillis(timeoutMs));
    }
}
