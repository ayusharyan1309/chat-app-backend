package com.ayush.chat.store;

import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.store.firestore.FirestoreMessageStoreFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Map;

/**
 * Conformance suite against the Firestore emulator, e.g.
 * {@code firebase emulators:start --only firestore --project demo-chat} then
 * {@code FIRESTORE_EMULATOR_HOST=127.0.0.1:8080 mvn test -Dtest=FirestoreMessageStoreTest}.
 */
@EnabledIfEnvironmentVariable(named = "FIRESTORE_EMULATOR_HOST", matches = ".+")
class FirestoreMessageStoreTest extends MessageStoreConformanceTest {

    @Override
    protected MessageStore createStore() throws Exception {
        FirestoreMessageStoreFactory factory = new FirestoreMessageStoreFactory();
        return factory.create(ConnectorSettings.withDefaults(factory.descriptor(),
                Map.of("projectId", "demo-chat-conformance")));
    }
}
