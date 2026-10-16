package com.ayush.chat.store.memory;

import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorFactory;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.store.MessageStore;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class InMemoryMessageStoreFactory implements ConnectorFactory<MessageStore> {

    @Override
    public ConnectorDescriptor descriptor() {
        return new ConnectorDescriptor(ConnectorKind.MESSAGE_STORE, "memory", "In-memory",
                "Non-durable store for demos and tests. Data is lost on restart.",
                "Embedded", "GA", List.of(), List.of());
    }

    @Override
    public MessageStore create(ConnectorSettings settings) {
        return new InMemoryMessageStore();
    }
}
