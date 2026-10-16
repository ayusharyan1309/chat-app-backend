package com.ayush.chat.store.memory;

import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorFactory;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.core.connector.Maturity;
import com.ayush.chat.store.MessageStore;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class InMemoryMessageStoreFactory implements ConnectorFactory<MessageStore> {

    private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(ConnectorKind.MESSAGE_STORE,
            "memory", "In-memory",
            "Non-durable store for demos and tests. Data is lost on restart.",
            "Embedded", Maturity.GA, List.of(), List.of());

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public MessageStore create(ConnectorSettings settings) {
        return new InMemoryMessageStore();
    }
}
