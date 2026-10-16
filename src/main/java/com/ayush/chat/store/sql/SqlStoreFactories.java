package com.ayush.chat.store.sql;

import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.core.connector.Maturity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Descriptor variants of the JDBC store. Each is its own connector so the dashboard can present
 * provider-specific defaults and guidance, while sharing one implementation. All of them run the
 * store conformance suite in CI (H2 in-process; PostgreSQL and MySQL as service containers;
 * Supabase is the PostgreSQL code path).
 */
public final class SqlStoreFactories {

    private SqlStoreFactories() {
    }

    @Component
    public static class H2 extends AbstractSqlStoreFactory {

        private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(ConnectorKind.MESSAGE_STORE,
                "h2", "H2 (embedded)",
                "Embedded SQL database stored in a local file. Great for development and single-node installs.",
                "SQL", Maturity.GA, List.of(),
                commonFields("jdbc:h2:file:./data/chat-store", "jdbc:h2:file:./data/chat-store", true));

        @Override
        public ConnectorDescriptor descriptor() {
            return DESCRIPTOR;
        }
    }

    @Component
    public static class Postgres extends AbstractSqlStoreFactory {

        private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(ConnectorKind.MESSAGE_STORE,
                "postgresql", "PostgreSQL",
                "PostgreSQL or any wire-compatible database. Recommended default for production.",
                "SQL", Maturity.GA,
                List.of("Amazon RDS / Aurora PostgreSQL", "Google Cloud SQL / AlloyDB", "Azure Database for PostgreSQL",
                        "Neon", "Supabase", "CockroachDB", "YugabyteDB", "Timescale"),
                commonFields("jdbc:postgresql://localhost:5432/chat", null, true));

        @Override
        public ConnectorDescriptor descriptor() {
            return DESCRIPTOR;
        }
    }

    @Component
    public static class MySql extends AbstractSqlStoreFactory {

        private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(ConnectorKind.MESSAGE_STORE,
                "mysql", "MySQL / MariaDB",
                "MySQL 8+ or MariaDB 10.5+, and MySQL-compatible distributed databases.",
                "SQL", Maturity.GA,
                List.of("MariaDB", "Amazon RDS / Aurora MySQL", "Google Cloud SQL for MySQL", "Azure Database for MySQL",
                        "PlanetScale", "TiDB"),
                commonFields("jdbc:mysql://localhost:3306/chat", null, true));

        @Override
        public ConnectorDescriptor descriptor() {
            return DESCRIPTOR;
        }
    }

    /** Supabase is PostgreSQL: accept the dashboard connection string and enforce TLS. */
    @Component
    public static class Supabase extends AbstractSqlStoreFactory {

        private static final String URL_PLACEHOLDER =
                "postgresql://postgres.[project-ref]:[password]@aws-0-[region].pooler.supabase.com:5432/postgres";

        private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(ConnectorKind.MESSAGE_STORE,
                "supabase", "Supabase",
                "Supabase Postgres via its connection pooler. Uses the same schema as PostgreSQL with TLS enforced.",
                "SQL", Maturity.GA, List.of("Supabase"), fields());

        private static List<ConfigField> fields() {
            List<ConfigField> fields = new ArrayList<>(commonFields(URL_PLACEHOLDER, null, true));
            fields.set(0, ConfigField.of("url", "Connection string").secret().required()
                    .placeholder(URL_PLACEHOLDER)
                    .description("Supabase dashboard → Project Settings → Database → Connection string (session pooler)")
                    .build());
            return List.copyOf(fields);
        }

        @Override
        public ConnectorDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        protected String adjustUrl(String jdbcUrl, ConnectorSettings settings) {
            if (jdbcUrl.contains("sslmode=")) {
                return jdbcUrl;
            }
            return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "sslmode=require";
        }
    }
}
