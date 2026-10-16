package com.ayush.chat.cache.memory;

import com.ayush.chat.cache.CacheStore;
import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorFactory;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.core.connector.Maturity;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class InMemoryCacheFactory implements ConnectorFactory<CacheStore> {

    private static final int DEFAULT_MAX_ENTRIES = 1_000_000;

    private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(ConnectorKind.CACHE,
            "memory", "In-memory",
            "In-process cache. Zero setup, single node only — use Redis when running more than one node.",
            "Embedded", Maturity.GA, List.of(),
            List.of(ConfigField.of("maxEntries", "Max entries").integer(DEFAULT_MAX_ENTRIES)
                    .description("Upper bound before expired entries are swept").build()));

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public CacheStore create(ConnectorSettings settings) {
        return new InMemoryCacheStore(settings.integer("maxEntries", DEFAULT_MAX_ENTRIES));
    }
}
