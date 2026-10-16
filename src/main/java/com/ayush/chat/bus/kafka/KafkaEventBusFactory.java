package com.ayush.chat.bus.kafka;

import com.ayush.chat.bus.EventBus;
import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorFactory;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.core.connector.Maturity;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** GA: the event bus conformance suite runs in CI against an embedded single-broker KRaft cluster. */
@Component
public class KafkaEventBusFactory implements ConnectorFactory<EventBus> {

    static final List<String> SECURITY_PROTOCOLS = List.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL");
    static final List<String> SASL_MECHANISMS = List.of("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512");
    private static final String DEFAULT_SECURITY_PROTOCOL = "PLAINTEXT";
    private static final String DEFAULT_SASL_MECHANISM = "PLAIN";
    private static final int DEFAULT_PARTITIONS = 12;
    private static final int DEFAULT_REPLICATION_FACTOR = 1;
    private static final boolean DEFAULT_AUTO_CREATE_TOPICS = true;

    private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(ConnectorKind.EVENT_BUS,
            "kafka", "Apache Kafka",
            "Durable, partitioned log. Per-key ordering, consumer groups for work queues, scales to many nodes.",
            "Streaming", Maturity.GA,
            List.of("Redpanda", "Amazon MSK", "Confluent Cloud", "Azure Event Hubs (Kafka API)", "Aiven"),
            List.of(ConfigField.of("bootstrapServers", "Bootstrap servers").required()
                            .placeholder("broker1:9092,broker2:9092")
                            .description("Comma-separated host:port list").build(),
                    ConfigField.of("topicPrefix", "Topic prefix").defaultValue("chat.")
                            .description("Prepended to every topic and consumer group id").build(),
                    ConfigField.of("securityProtocol", "Security protocol")
                            .select(DEFAULT_SECURITY_PROTOCOL, SECURITY_PROTOCOLS.toArray(String[]::new))
                            .description("SASL_SSL for Confluent Cloud, Event Hubs and MSK (SCRAM)").build(),
                    ConfigField.of("saslMechanism", "SASL mechanism")
                            .select(DEFAULT_SASL_MECHANISM, SASL_MECHANISMS.toArray(String[]::new))
                            .description("Used with SASL_PLAINTEXT / SASL_SSL").build(),
                    ConfigField.of("username", "SASL username")
                            .description("API key for Confluent Cloud; $ConnectionString for Azure Event Hubs").build(),
                    ConfigField.of("password", "SASL password").secret()
                            .description("API secret, SCRAM password or Event Hubs connection string").build(),
                    ConfigField.of("partitions", "Partitions").integer(DEFAULT_PARTITIONS)
                            .description("Partitions for auto-created topics; bounds work-queue parallelism").build(),
                    ConfigField.of("replicationFactor", "Replication factor").integer(DEFAULT_REPLICATION_FACTOR)
                            .description("Replicas for auto-created topics (3 in production)").build(),
                    ConfigField.of("autoCreateTopics", "Create topics").bool(DEFAULT_AUTO_CREATE_TOPICS)
                            .description("Create missing topics on first use; disable when topics are managed externally").build()));

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    /** Settings arrive with the descriptor's defaults applied ({@link ConnectorSettings#withDefaults}). */
    @Override
    public EventBus create(ConnectorSettings settings) {
        int partitions = settings.integer("partitions", DEFAULT_PARTITIONS);
        int replicationFactor = settings.integer("replicationFactor", DEFAULT_REPLICATION_FACTOR);
        if (partitions < 1) {
            throw new IllegalArgumentException("Partitions must be at least 1");
        }
        if (replicationFactor < 1 || replicationFactor > Short.MAX_VALUE) {
            throw new IllegalArgumentException("Replication factor must be at least 1");
        }
        return new KafkaEventBus(clientConfig(settings), settings.string("topicPrefix"), partitions,
                (short) replicationFactor, settings.bool("autoCreateTopics", DEFAULT_AUTO_CREATE_TOPICS));
    }

    /** Connection properties shared by the producer, consumers and admin client. */
    public static Map<String, Object> clientConfig(ConnectorSettings settings) {
        Map<String, Object> props = new HashMap<>();
        props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, settings.required("bootstrapServers").trim());
        String protocol = settings.string("securityProtocol", DEFAULT_SECURITY_PROTOCOL).trim().toUpperCase(Locale.ROOT);
        if (!SECURITY_PROTOCOLS.contains(protocol)) {
            throw new IllegalArgumentException("Unsupported security protocol: " + protocol);
        }
        props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, protocol);
        if (protocol.startsWith("SASL_")) {
            String mechanism = settings.string("saslMechanism", DEFAULT_SASL_MECHANISM).trim().toUpperCase(Locale.ROOT);
            if (!SASL_MECHANISMS.contains(mechanism)) {
                throw new IllegalArgumentException("Unsupported SASL mechanism: " + mechanism);
            }
            if (!settings.has("username") || !settings.has("password")) {
                throw new IllegalArgumentException("SASL username and password are required for " + protocol);
            }
            props.put(SaslConfigs.SASL_MECHANISM, mechanism);
            props.put(SaslConfigs.SASL_JAAS_CONFIG, jaasConfig(mechanism, settings.string("username"), settings.string("password")));
        }
        return props;
    }

    static String jaasConfig(String mechanism, String username, String password) {
        String module = mechanism.startsWith("SCRAM")
                ? "org.apache.kafka.common.security.scram.ScramLoginModule"
                : "org.apache.kafka.common.security.plain.PlainLoginModule";
        return module + " required username=\"" + quote(username) + "\" password=\"" + quote(password) + "\";";
    }

    private static String quote(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
