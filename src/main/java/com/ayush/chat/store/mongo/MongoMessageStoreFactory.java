package com.ayush.chat.store.mongo;

import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorFactory;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.core.connector.Maturity;
import com.ayush.chat.store.MessageStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * GA: the store conformance suite runs in CI against an in-process MongoDB wire-protocol server
 * (mongo-java-server), exercising the real driver end to end.
 */
@Component
public class MongoMessageStoreFactory implements ConnectorFactory<MessageStore> {

    private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(ConnectorKind.MESSAGE_STORE,
            "mongodb", "MongoDB",
            "Document store using single-document atomic operations only: per-conversation sequence "
                    + "numbers via $inc, idempotent sends via unique claim documents. Works on standalone "
                    + "servers, replica sets and sharded clusters; no multi-document transactions required.",
            "Document", Maturity.GA,
            List.of("MongoDB Atlas", "Amazon DocumentDB", "Azure Cosmos DB (Mongo API)", "FerretDB"),
            List.of(ConfigField.of("uri", "Connection string").secret().required()
                            .placeholder("mongodb://user:pass@host:27017")
                            .description("mongodb:// or mongodb+srv:// URI including credentials, TLS and "
                                    + "replica-set options").build(),
                    ConfigField.of("database", "Database").defaultValue("chat")
                            .description("Database holding the chat collections; created on first write").build(),
                    ConfigField.of("collectionPrefix", "Collection prefix").defaultValue("chat_")
                            .description("Prepended to every collection name so several stores can share a database")
                            .build()));

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    /** Settings arrive with the descriptor's defaults applied ({@link ConnectorSettings#withDefaults}). */
    @Override
    public MessageStore create(ConnectorSettings settings) {
        return new MongoMessageStore(settings.required("uri"), settings.required("database"),
                settings.string("collectionPrefix"));
    }
}
