package com.ayush.chat.store;

import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.core.exception.InfrastructureUnavailableException;
import com.ayush.chat.core.util.Ids;
import com.ayush.chat.store.sql.AbstractSqlStoreFactory;
import com.ayush.chat.store.sql.SqlStoreFactories;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the conformance suite against every SQL dialect. H2 always runs; PostgreSQL and MySQL run
 * when TEST_POSTGRES_URL / TEST_MYSQL_URL are set (JDBC URL with credentials as query params or
 * user/password env vars).
 */
class SqlMessageStoreTests {

    private static MessageStore create(AbstractSqlStoreFactory factory, Map<String, Object> raw) {
        return factory.create(ConnectorSettings.withDefaults(factory.descriptor(), raw));
    }

    @Nested
    class H2InMemory extends MessageStoreConformanceTest {
        @Override
        protected MessageStore createStore() {
            return create(new SqlStoreFactories.H2(), Map.of(
                    "url", "jdbc:h2:mem:store-" + Ids.randomToken(6) + ";DB_CLOSE_DELAY=-1",
                    "username", "sa", "poolSize", 10));
        }

        @Test
        void databaseFailuresAreUnavailable() {
            store.close(); // the pool is gone, as when the database is unreachable
            assertThatThrownBy(() -> store.findUser(tenant, "alice"))
                    .isInstanceOf(InfrastructureUnavailableException.class);
            assertThatThrownBy(() -> direct("alice", "bob"))
                    .isInstanceOf(InfrastructureUnavailableException.class);
        }
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
    class Postgres extends MessageStoreConformanceTest {
        @Override
        protected MessageStore createStore() {
            return create(new SqlStoreFactories.Postgres(), Map.of(
                    "url", System.getenv("TEST_POSTGRES_URL"),
                    "username", System.getenv().getOrDefault("TEST_POSTGRES_USER", ""),
                    "password", System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", ""),
                    "poolSize", 10));
        }
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = "TEST_MYSQL_URL", matches = ".+")
    class MySql extends MessageStoreConformanceTest {
        @Override
        protected MessageStore createStore() {
            return create(new SqlStoreFactories.MySql(), Map.of(
                    "url", System.getenv("TEST_MYSQL_URL"),
                    "username", System.getenv().getOrDefault("TEST_MYSQL_USER", "root"),
                    "password", System.getenv().getOrDefault("TEST_MYSQL_PASSWORD", ""),
                    "poolSize", 10));
        }
    }
}
