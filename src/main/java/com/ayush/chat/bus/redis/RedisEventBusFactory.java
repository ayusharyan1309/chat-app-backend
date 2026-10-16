package com.ayush.chat.bus.redis;

import com.ayush.chat.bus.EventBus;
import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorFactory;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.core.connector.Maturity;
import org.springframework.stereotype.Component;

import java.util.List;

/** GA: the event bus conformance suite runs in CI against a live Redis service container. */
@Component
public class RedisEventBusFactory implements ConnectorFactory<EventBus> {

    private static final int DEFAULT_STREAM_MAX_LEN = 100_000;

    private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(ConnectorKind.EVENT_BUS,
            "redis", "Redis",
            "Pub/Sub for fan-out and Streams consumer groups for work queues. Simple to run; "
                    + "fan-out is not replayed after a disconnect (use Kafka when that matters).",
            "Key-value", Maturity.GA,
            List.of("Valkey", "KeyDB", "Dragonfly", "Amazon ElastiCache", "Azure Cache for Redis"),
            List.of(ConfigField.of("uri", "Connection URI").secret().required().defaultValue("redis://localhost:6379")
                            .placeholder("rediss://:password@host:6380/0")
                            .description("redis:// or rediss:// (TLS); standalone or redis-sentinel://, not Redis Cluster").build(),
                    ConfigField.of("channelPrefix", "Channel prefix").defaultValue("chat.")
                            .description("Prepended to Pub/Sub channels and stream keys").build(),
                    ConfigField.of("streamMaxLen", "Stream max length").integer(DEFAULT_STREAM_MAX_LEN)
                            .description("Approximate cap per work-queue stream (MAXLEN ~); "
                                    + "consumers further behind lose the oldest entries").build()));

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    /** Settings arrive with the descriptor's defaults applied ({@link ConnectorSettings#withDefaults}). */
    @Override
    public EventBus create(ConnectorSettings settings) {
        long maxLen = settings.longValue("streamMaxLen", DEFAULT_STREAM_MAX_LEN);
        if (maxLen < 1) {
            throw new IllegalArgumentException("Stream max length must be at least 1");
        }
        return new RedisEventBus(settings.required("uri"), settings.string("channelPrefix"), maxLen);
    }
}
