package com.ayush.chat.core.connector;

/**
 * Creates connectors of one provider. Register an implementation as a Spring bean and it is
 * discovered by the {@link ConnectorRegistry}, listed in the dashboard and selectable via config.
 */
public interface ConnectorFactory<T extends Connector> {

    ConnectorDescriptor descriptor();

    /** Build a ready-to-use connector. Implementations should fail fast on bad settings. */
    T create(ConnectorSettings settings) throws Exception;

    default ConnectorKind kind() {
        return descriptor().kind();
    }

    default String id() {
        return descriptor().id();
    }
}
