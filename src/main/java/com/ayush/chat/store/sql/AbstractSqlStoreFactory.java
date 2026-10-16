package com.ayush.chat.store.sql;

import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorFactory;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.store.MessageStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared wiring for the JDBC-backed message stores: connection pool + {@link SqlMessageStore}.
 */
public abstract class AbstractSqlStoreFactory implements ConnectorFactory<MessageStore> {

    private static final int DEFAULT_POOL_SIZE = 20;
    private static final int DEFAULT_CONNECTION_TIMEOUT_MS = 10_000;

    protected static List<ConfigField> commonFields(String urlPlaceholder, Object defaultUrl, boolean credentials) {
        List<ConfigField> fields = new ArrayList<>();
        // Secret: provider URIs routinely embed the password (postgresql://user:pass@host/db).
        fields.add(ConfigField.of("url", "Connection URL").secret().required().defaultValue(defaultUrl).placeholder(urlPlaceholder)
                .description("JDBC URL or a provider URI such as postgresql://user:pass@host:5432/db").build());
        if (credentials) {
            fields.add(ConfigField.of("username", "Username").build());
            fields.add(ConfigField.of("password", "Password").secret().build());
        }
        fields.add(ConfigField.of("poolSize", "Connection pool size").integer(DEFAULT_POOL_SIZE)
                .description("Maximum open connections per node").build());
        fields.add(ConfigField.of("connectionTimeoutMs", "Connection timeout (ms)").integer(DEFAULT_CONNECTION_TIMEOUT_MS).build());
        fields.add(ConfigField.of("tablePrefix", "Table prefix").defaultValue("chat_")
                .description("Letters, digits and underscores; lets several deployments share one schema").build());
        return List.copyOf(fields);
    }

    /** Hook for provider-specific JDBC URL adjustments (e.g. enforcing TLS). */
    protected String adjustUrl(String jdbcUrl, ConnectorSettings settings) {
        return jdbcUrl;
    }

    /** Settings arrive with the descriptor's defaults applied ({@link ConnectorSettings#withDefaults}). */
    @Override
    public MessageStore create(ConnectorSettings settings) {
        JdbcUrls.Normalized url = JdbcUrls.normalize(settings.required("url"),
                settings.string("username"), settings.string("password"));
        String jdbcUrl = adjustUrl(url.jdbcUrl(), settings);
        int connectionTimeoutMs = settings.integer("connectionTimeoutMs", DEFAULT_CONNECTION_TIMEOUT_MS);
        HikariConfig config = new HikariConfig();
        config.setPoolName("store-" + id() + "-" + Integer.toHexString(System.identityHashCode(config)));
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(url.username());
        config.setPassword(url.password());
        config.setMaximumPoolSize(settings.integer("poolSize", DEFAULT_POOL_SIZE));
        config.setMinimumIdle(Math.min(2, config.getMaximumPoolSize()));
        config.setConnectionTimeout(connectionTimeoutMs);
        config.setInitializationFailTimeout(connectionTimeoutMs);
        HikariDataSource dataSource = new HikariDataSource(config);
        try {
            return new SqlMessageStore(id(), dataSource, settings.string("tablePrefix"));
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
    }
}
