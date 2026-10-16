package com.ayush.chat.store;

import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.store.cassandra.CassandraMessageStoreFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.HashMap;
import java.util.Map;

/**
 * Conformance suite against a real Cassandra/ScyllaDB cluster, e.g.
 * {@code docker run -p 9042:9042 cassandra:5} then
 * {@code CASSANDRA_CONTACT_POINTS=127.0.0.1:9042 mvn test -Dtest=CassandraMessageStoreTest}.
 * Optional: {@code CASSANDRA_LOCAL_DC}, {@code CASSANDRA_USERNAME}, {@code CASSANDRA_PASSWORD}.
 */
@EnabledIfEnvironmentVariable(named = "CASSANDRA_CONTACT_POINTS", matches = ".+")
class CassandraMessageStoreTest extends MessageStoreConformanceTest {

    @Override
    protected MessageStore createStore() throws Exception {
        Map<String, Object> raw = new HashMap<>();
        raw.put("contactPoints", System.getenv("CASSANDRA_CONTACT_POINTS"));
        raw.put("keyspace", "chat_conformance");
        raw.put("localDatacenter", System.getenv("CASSANDRA_LOCAL_DC"));
        raw.put("username", System.getenv("CASSANDRA_USERNAME"));
        raw.put("password", System.getenv("CASSANDRA_PASSWORD"));
        CassandraMessageStoreFactory factory = new CassandraMessageStoreFactory();
        return factory.create(ConnectorSettings.withDefaults(factory.descriptor(), raw));
    }
}
