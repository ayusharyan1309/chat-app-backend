package com.ayush.chat.core.connector;

/**
 * A live connection to an infrastructure provider (a database, cache, broker, ...).
 * Instances are created by a {@link ConnectorFactory} and may be swapped at runtime.
 */
public interface Connector extends AutoCloseable {

    /** Provider id, matching {@link ConnectorDescriptor#id()}. */
    String providerId();

    HealthStatus health();

    /** Release pools, clients and threads. Must be idempotent and must not throw. */
    @Override
    void close();
}
