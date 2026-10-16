package com.ayush.chat.cache.redis;

import com.ayush.chat.cache.CacheStore;
import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorFactory;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.Maturity;
import com.ayush.chat.core.connector.ConnectorSettings;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class RedisCacheFactory implements ConnectorFactory<CacheStore> {

    @Override
    public ConnectorDescriptor descriptor() {
        return new ConnectorDescriptor(ConnectorKind.CACHE, "redis", "Redis",
                "Shared cache for multi-node deployments (presence, counters, rate limits, locks).",
                "Key-value", Maturity.GA,
                List.of("Valkey", "KeyDB", "Dragonfly", "Amazon ElastiCache", "Azure Cache for Redis", "Upstash"),
                List.of(ConfigField.of("uri", "Connection URI").secret().required().defaultValue("redis://localhost:6379")
                                .placeholder("rediss://:password@host:6380/0")
                                .description("redis:// or rediss:// (TLS); password, user and database index go in the URI. "
                                        + "redis-sentinel:// is also accepted").build(),
                        ConfigField.of("keyPrefix", "Key prefix").defaultValue("chat:")
                                .description("Prepended to every key so one Redis can be shared").build(),
                        ConfigField.of("timeoutMs", "Timeout (ms)").integer(3000)
                                .description("Connect and command timeout").build()));
    }

    @Override
    public CacheStore create(ConnectorSettings settings) {
        int timeoutMs = settings.integer("timeoutMs", 3000);
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        return new RedisCacheStore(settings.required("uri"), settings.string("keyPrefix", ""),
                Duration.ofMillis(timeoutMs));
    }
}
