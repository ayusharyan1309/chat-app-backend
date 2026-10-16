package com.ayush.chat.bus;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.ayush.chat.bus.kafka.KafkaEventBusFactory;
import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.config.SaslConfigs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the bus contract against an embedded single-broker KRaft cluster (no Spring context). */
class KafkaEventBusTest extends EventBusConformanceTest {

    private static final String PREFIX = "chat.test.";
    private static EmbeddedKafkaKraftBroker broker;

    private final KafkaEventBusFactory factory = new KafkaEventBusFactory();

    @BeforeAll
    static void startBroker() {
        for (String noisy : List.of("org.apache.kafka", "kafka", "state.change.logger", "org.springframework.kafka")) {
            ((Logger) LoggerFactory.getLogger(noisy)).setLevel(Level.WARN);
        }
        broker = new EmbeddedKafkaKraftBroker(1, 1);
        // Topics must come from the connector's AdminClient, not broker-side auto-creation.
        broker.brokerProperties(Map.of("auto.create.topics.enable", "false", "group.initial.rebalance.delay.ms", "0"));
        broker.afterPropertiesSet();
    }

    @AfterAll
    static void stopBroker() {
        if (broker != null) {
            broker.destroy();
        }
    }

    @Override
    protected EventBus createBus() {
        return factory.create(settings(Map.of("bootstrapServers", broker.getBrokersAsString(),
                "topicPrefix", PREFIX, "partitions", 4)));
    }

    @Override
    protected EventBus createPeer() {
        return createBus();
    }

    @Override
    protected Duration timeout() {
        return Duration.ofSeconds(45);
    }

    @Override
    protected Duration quietPeriod() {
        return Duration.ofMillis(1500);
    }

    private ConnectorSettings settings(Map<String, Object> raw) {
        return ConnectorSettings.withDefaults(factory.descriptor(), raw);
    }

    @Test
    void descriptorDeclaresKafkaBus() {
        ConnectorDescriptor d = factory.descriptor();
        assertThat(d.kind()).isEqualTo(ConnectorKind.EVENT_BUS);
        assertThat(d.id()).isEqualTo("kafka");
        assertThat(d.maturity()).isEqualTo("GA");
        assertThat(d.compatibleWith()).contains("Redpanda", "Amazon MSK", "Confluent Cloud",
                "Azure Event Hubs (Kafka API)", "Aiven");
        assertThat(d.field("bootstrapServers").required()).isTrue();
        assertThat(d.field("topicPrefix").defaultValue()).isEqualTo("chat.");
        assertThat(d.field("securityProtocol").options()).containsExactly("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL");
        assertThat(d.field("saslMechanism").options()).containsExactly("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512");
        assertThat(d.field("password").type()).isEqualTo(ConfigField.FieldType.SECRET);
        assertThat(d.field("partitions").defaultValue()).isEqualTo(12);
        assertThat(d.field("replicationFactor").defaultValue()).isEqualTo(1);
        assertThat(d.field("autoCreateTopics").defaultValue()).isEqualTo(true);
    }

    @Test
    void createsMissingTopicWithConfiguredPartitions() throws Exception {
        bus.subscribe(topic, null, m -> { });
        TopicDescription description = broker.doWithAdminFunction(admin -> {
            try {
                return admin.describeTopics(List.of(PREFIX + topic)).allTopicNames().get(10, TimeUnit.SECONDS).get(PREFIX + topic);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(description.partitions()).hasSize(4);
    }

    @Test
    void healthReportsCluster() {
        assertThat(bus.health().details()).containsEntry("brokers", 1);
    }

    @Test
    void saslSettingsBuildJaasConfigAndRequireCredentials() {
        Map<String, Object> raw = new HashMap<>(Map.of("bootstrapServers", "b:9092", "securityProtocol", "SASL_SSL",
                "saslMechanism", "SCRAM-SHA-512", "username", "svc", "password", "p\"w\\d"));
        Map<String, Object> props = KafkaEventBusFactory.clientConfig(settings(raw));
        assertThat(props).containsEntry("security.protocol", "SASL_SSL").containsEntry(SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-512");
        assertThat((String) props.get(SaslConfigs.SASL_JAAS_CONFIG))
                .isEqualTo("org.apache.kafka.common.security.scram.ScramLoginModule required username=\"svc\" password=\"p\\\"w\\\\d\";");

        raw.remove("password");
        assertThatThrownBy(() -> KafkaEventBusFactory.clientConfig(settings(raw)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("password");
        assertThatThrownBy(() -> KafkaEventBusFactory.clientConfig(settings(Map.of("bootstrapServers", "b:9092",
                "securityProtocol", "SASL_TLS")))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plaintextSettingsCarryNoSaslConfig() {
        Map<String, Object> props = KafkaEventBusFactory.clientConfig(settings(Map.of("bootstrapServers", " b1:9092,b2:9092 ")));
        assertThat(props).containsEntry("bootstrap.servers", "b1:9092,b2:9092").containsEntry("security.protocol", "PLAINTEXT")
                .doesNotContainKey(SaslConfigs.SASL_JAAS_CONFIG);
    }

    @Test
    void unreachableClusterFailsFast() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> factory.create(settings(Map.of("bootstrapServers", "127.0.0.1:1"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Cannot reach Kafka");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(30));
    }
}
