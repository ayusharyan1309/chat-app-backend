package com.ayush.chat.store.cassandra;

import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorFactory;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.store.MessageStore;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

@Component
public class CassandraMessageStoreFactory implements ConnectorFactory<MessageStore> {

    static final int DEFAULT_PORT = 9042;

    @Override
    public ConnectorDescriptor descriptor() {
        return new ConnectorDescriptor(ConnectorKind.MESSAGE_STORE, "cassandra", "Apache Cassandra",
                "Wide-column store for very high write volumes. Query-first tables partitioned by tenant; "
                        + "per-conversation sequence numbers via lightweight transactions; messages bucketed "
                        + "1000 per partition. Tables are created on connect.",
                "Wide-column", "BETA",
                List.of("ScyllaDB", "Amazon Keyspaces", "Azure Managed Instance for Apache Cassandra", "DataStax Astra"),
                List.of(ConfigField.of("contactPoints", "Contact points").required()
                                .placeholder("127.0.0.1:9042,10.0.0.2:9042")
                                .description("Comma-separated host[:port] list; port defaults to 9042").build(),
                        ConfigField.of("localDatacenter", "Local datacenter").defaultValue("datacenter1")
                                .description("Datacenter the driver routes to; must match the cluster's DC name").build(),
                        ConfigField.of("keyspace", "Keyspace").defaultValue("chat")
                                .description("Letters, digits and underscores").build(),
                        ConfigField.of("username", "Username")
                                .description("Leave empty for clusters without authentication").build(),
                        ConfigField.of("password", "Password").secret().build(),
                        ConfigField.of("createKeyspace", "Create keyspace").bool(true)
                                .description("Create the keyspace if missing (NetworkTopologyStrategy in the local "
                                        + "DC). Disable on managed services that do not allow it").build(),
                        ConfigField.of("replicationFactor", "Replication factor").integer(1)
                                .description("Used only when the keyspace is created; 3 for production").build(),
                        ConfigField.of("tablePrefix", "Table prefix").defaultValue("")
                                .description("Optional prefix for table names so several stores can share a keyspace")
                                .build()));
    }

    @Override
    public MessageStore create(ConnectorSettings settings) {
        return new CassandraMessageStore(new CassandraMessageStore.Options(
                parseContactPoints(settings.required("contactPoints")),
                settings.string("localDatacenter", "datacenter1"),
                settings.string("keyspace", "chat"),
                settings.string("username", null),
                settings.string("password", null),
                settings.bool("createKeyspace", true),
                settings.integer("replicationFactor", 1),
                settings.string("tablePrefix", "")));
    }

    /** Parses {@code host[:port]} entries separated by commas or whitespace; IPv6 as {@code [addr]:port}. */
    static List<InetSocketAddress> parseContactPoints(String raw) {
        List<InetSocketAddress> points = new ArrayList<>();
        for (String token : raw.trim().split("[,\\s]+")) {
            if (token.isEmpty()) {
                continue;
            }
            String host = token;
            int port = DEFAULT_PORT;
            if (token.startsWith("[")) {
                int end = token.indexOf(']');
                if (end < 0) {
                    throw new IllegalArgumentException("Invalid contact point: " + token);
                }
                host = token.substring(1, end);
                if (token.length() > end + 1) {
                    port = parsePort(token, token.substring(end + 1).replaceFirst("^:", ""));
                }
            } else if (token.indexOf(':') >= 0 && token.indexOf(':') == token.lastIndexOf(':')) {
                host = token.substring(0, token.indexOf(':'));
                port = parsePort(token, token.substring(token.indexOf(':') + 1));
            }
            points.add(new InetSocketAddress(host, port));
        }
        if (points.isEmpty()) {
            throw new IllegalArgumentException("At least one contact point is required");
        }
        return points;
    }

    private static int parsePort(String token, String port) {
        try {
            return Integer.parseInt(port);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port in contact point: " + token);
        }
    }
}
