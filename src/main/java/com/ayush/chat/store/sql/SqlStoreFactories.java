package com.ayush.chat.store.sql;

import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.Maturity;
import com.ayush.chat.core.connector.ConnectorSettings;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Descriptor variants of the JDBC store. Each is its own connector so the dashboard can present
 * provider-specific defaults and guidance, while sharing one implementation.
 */
public final class SqlStoreFactories {

    private SqlStoreFactories() {
    }

    @Component
    public static class H2 extends AbstractSqlStoreFactory {
        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor(ConnectorKind.MESSAGE_STORE, "h2", "H2 (embedded)",
                    "Embedded SQL database stored in a local file. Great for development and single-node installs.",
                    "SQL", Maturity.GA, List.of(),
                    commonFields("jdbc:h2:file:./data/chat-store", "jdbc:h2:file:./data/chat-store", true));
        }
    }

    @Component
    public static class Postgres extends AbstractSqlStoreFactory {
        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor(ConnectorKind.MESSAGE_STORE, "postgresql", "PostgreSQL",
                    "PostgreSQL or any wire-compatible database. Recommended default for production.",
                    "SQL", Maturity.GA,
                    List.of("Amazon RDS / Aurora PostgreSQL", "Google Cloud SQL / AlloyDB", "Azure Database for PostgreSQL",
                            "Neon", "Supabase", "CockroachDB", "YugabyteDB", "Timescale"),
                    commonFields("jdbc:postgresql://localhost:5432/chat", null, true));
        }
    }

    @Component
    public static class MySql extends AbstractSqlStoreFactory {
        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor(ConnectorKind.MESSAGE_STORE, "mysql", "MySQL / MariaDB",
                    "MySQL 8+ or MariaDB 10.5+, and MySQL-compatible distributed databases.",
                    "SQL", Maturity.GA,
                    List.of("MariaDB", "Amazon RDS / Aurora MySQL", "Google Cloud SQL for MySQL", "Azure Database for MySQL",
                            "PlanetScale", "TiDB"),
                    commonFields("jdbc:mysql://localhost:3306/chat", null, true));
        }
    }

    /** Supabase is PostgreSQL: accept the dashboard connection string and enforce TLS. */
    @Component
    public static class Supabase extends AbstractSqlStoreFactory {
        @Override
        public ConnectorDescriptor descriptor() {
            List<ConfigField> fields = new ArrayList<>(commonFields(
                    "postgresql://postgres.[project-ref]:[password]@aws-0-[region].pooler.supabase.com:5432/postgres",
                    null, true));
            fields.set(0, ConfigField.of("url", "Connection string").secret().required()
                    .placeholder("postgresql://postgres.[project-ref]:[password]@aws-0-[region].pooler.supabase.com:5432/postgres")
                    .description("Supabase dashboard → Project Settings → Database → Connection string (session pooler)")
                    .build());
            return new ConnectorDescriptor(ConnectorKind.MESSAGE_STORE, "supabase", "Supabase",
                    "Supabase Postgres via its connection pooler. Uses the same schema as PostgreSQL with TLS enforced.",
                    "SQL", Maturity.GA, List.of("Supabase"), fields);
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
