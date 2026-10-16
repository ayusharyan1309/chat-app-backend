package com.ayush.chat.bus.memory;

import com.ayush.chat.bus.EventBus;
import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorFactory;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.core.connector.Maturity;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class InMemoryEventBusFactory implements ConnectorFactory<EventBus> {

    private static final int DEFAULT_LANES = 8;

    private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(ConnectorKind.EVENT_BUS,
            "memory", "In-memory",
            "In-process event bus. Zero setup, single node only — use Kafka or Redis for clusters.",
            "Embedded", Maturity.GA, List.of(),
            List.of(ConfigField.of("lanes", "Ordering lanes").integer(DEFAULT_LANES)
                    .description("Parallel single-threaded lanes; same key always uses the same lane").build()));

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public EventBus create(ConnectorSettings settings) {
        return new InMemoryEventBus(settings.integer("lanes", DEFAULT_LANES));
    }
}
