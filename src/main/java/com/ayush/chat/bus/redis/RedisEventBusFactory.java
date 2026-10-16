package com.ayush.chat.bus.redis;

import com.ayush.chat.bus.EventBus;
import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorFactory;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisEventBusFactory implements ConnectorFactory<EventBus> {

    @Override
    public ConnectorDescriptor descriptor() {
        return new ConnectorDescriptor(ConnectorKind.EVENT_BUS, "redis", "Redis",
                "Pub/Sub for fan-out and Streams consumer groups for work queues. Simple to run; "
                        + "fan-out is not replayed after a disconnect (use Kafka when that matters).",
                "Key-value", "GA",
                List.of("Valkey", "KeyDB", "Dragonfly", "Amazon ElastiCache", "Azure Cache for Redis"),
                List.of(ConfigField.of("uri", "Connection URI").secret().required().defaultValue("redis://localhost:6379")
                                .placeholder("rediss://:password@host:6380/0")
                                .description("redis:// or rediss:// (TLS); standalone or redis-sentinel://, not Redis Cluster").build(),
                        ConfigField.of("channelPrefix", "Channel prefix").defaultValue("chat.")
                                .description("Prepended to Pub/Sub channels and stream keys").build(),
                        ConfigField.of("streamMaxLen", "Stream max length").integer(100_000)
                                .description("Approximate cap per work-queue stream (MAXLEN ~); "
                                        + "consumers further behind lose the oldest entries").build()));
    }

    @Override
    public EventBus create(ConnectorSettings settings) {
        long maxLen = settings.longValue("streamMaxLen", 100_000);
        if (maxLen < 1) {
            throw new IllegalArgumentException("Stream max length must be at least 1");
        }
        return new RedisEventBus(settings.required("uri"), settings.string("channelPrefix", ""), maxLen);
    }
}
