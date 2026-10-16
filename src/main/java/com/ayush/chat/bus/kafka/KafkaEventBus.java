package com.ayush.chat.bus.kafka;

import com.ayush.chat.bus.EventBus;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorRegistry;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.error.InfrastructureUnavailableException;
import com.ayush.chat.core.util.Ids;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Kafka-backed bus (also Redpanda, MSK, Confluent Cloud, Event Hubs' Kafka endpoint).
 *
 * <ul>
 *   <li>Publish: idempotent producer, {@code acks=all}, record key = message key, so a key always
 *       lands on the same partition and keeps its order.</li>
 *   <li>Broadcast: each subscription has its own consumer group
 *       ({@code <prefix>broadcast.<instance>.<n>}) starting at the latest offset, so every node sees
 *       every message published after it subscribed. Offsets are never committed; the empty group
 *       is dropped by the broker once the subscription closes.</li>
 *   <li>Work queue: members share the group {@code <prefix><group>.<topic>}; Kafka assigns each
 *       partition to one member. Offsets are committed after the handler ran (at-least-once).
 *       A new group starts at the latest offset and that position is committed immediately, so
 *       messages published while every member is down are processed on restart.</li>
 * </ul>
 * Each subscription owns one polling thread, which keeps per-partition (and so per-key) order.
 *
 * <p>Failures to publish or subscribe (cluster unreachable, topic creation failed, bus closed)
 * surface as {@link InfrastructureUnavailableException} (503). Delivery failures reported later by
 * the producer callback are logged and counted in {@code publishFailures}.
 */
@Slf4j
public class KafkaEventBus implements EventBus {

    private static final String COMPONENT = ConnectorKind.EVENT_BUS.getDisplayName();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);
    private static final Duration ASSIGN_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(1);

    private final Map<String, Object> clientConfig;
    private final String topicPrefix;
    private final int partitions;
    private final short replicationFactor;
    private final boolean autoCreateTopics;
    private final String instanceId = Ids.randomToken(10).toLowerCase(Locale.ROOT);
    private final Admin admin;
    private final KafkaProducer<String, String> producer;
    private final Set<String> knownTopics = ConcurrentHashMap.newKeySet();
    private final Set<KafkaSubscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final AtomicInteger subscriptionSeq = new AtomicInteger();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong publishFailures = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * @param clientConfig connection settings shared by producer, consumers and admin client
     *                     (bootstrap servers, security protocol, SASL)
     */
    public KafkaEventBus(Map<String, Object> clientConfig, String topicPrefix, int partitions,
                         short replicationFactor, boolean autoCreateTopics) {
        this.clientConfig = Map.copyOf(clientConfig);
        this.topicPrefix = topicPrefix == null ? "" : topicPrefix;
        this.partitions = partitions;
        this.replicationFactor = replicationFactor;
        this.autoCreateTopics = autoCreateTopics;

        Map<String, Object> adminProps = new HashMap<>(this.clientConfig);
        adminProps.put(CommonClientConfigs.CLIENT_ID_CONFIG, "chat-bus-admin-" + instanceId);
        Admin createdAdmin = Admin.create(adminProps);
        try {
            createdAdmin.describeCluster(new DescribeClusterOptions().timeoutMs((int) CONNECT_TIMEOUT.toMillis()))
                    .nodes().get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            createdAdmin.close(Duration.ZERO);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Cannot reach Kafka at " + clientConfig.get(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG),
                    e instanceof ExecutionException ? e.getCause() : e);
        }
        this.admin = createdAdmin;

        Map<String, Object> producerProps = new HashMap<>(this.clientConfig);
        producerProps.put(ProducerConfig.CLIENT_ID_CONFIG, "chat-bus-producer-" + instanceId);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        producerProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        producerProps.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        // Bound how long publish() may block on metadata when the cluster is unavailable.
        producerProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, (int) CONNECT_TIMEOUT.toMillis());
        try {
            this.producer = new KafkaProducer<>(producerProps);
        } catch (RuntimeException e) {
            admin.close(Duration.ZERO);
            throw e;
        }
    }

    @Override
    public String providerId() {
        return "kafka";
    }

    @Override
    public HealthStatus health() {
        if (closed.get()) {
            return HealthStatus.down("closed");
        }
        try {
            DescribeClusterResult cluster = admin.describeCluster(new DescribeClusterOptions().timeoutMs(5000));
            int brokers = cluster.nodes().get(5, TimeUnit.SECONDS).size();
            String clusterId = cluster.clusterId().get(5, TimeUnit.SECONDS);
            return HealthStatus.up("Connected to Kafka cluster " + clusterId, Map.of(
                    "brokers", brokers,
                    "subscriptions", subscriptions.size(),
                    "published", published.get(),
                    "publishFailures", publishFailures.get()));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return HealthStatus.down("Kafka unreachable: " + ConnectorRegistry.rootMessage(e));
        }
    }

    @Override
    public void publish(String topic, String key, String payload) {
        ensureOpen();
        String name = topicName(topic);
        ensureTopic(name);
        try {
            producer.send(new ProducerRecord<>(name, key, payload), (metadata, error) -> {
                if (error != null) {
                    publishFailures.incrementAndGet();
                    log.error("Kafka publish to {} failed", name, error);
                }
            });
        } catch (KafkaException e) {
            publishFailures.incrementAndGet();
            throw new InfrastructureUnavailableException(COMPONENT, e); // e.g. metadata unavailable within max.block.ms
        }
        published.incrementAndGet();
    }

    @Override
    public Subscription subscribe(String topic, String group, Consumer<String> handler) {
        ensureOpen();
        Objects.requireNonNull(handler, "handler");
        String name = topicName(topic);
        ensureTopic(name);
        int n = subscriptionSeq.incrementAndGet();
        boolean workQueue = group != null;
        String groupId = workQueue ? topicPrefix + group + "." + topic : topicPrefix + "broadcast." + instanceId + "." + n;

        Map<String, Object> props = new HashMap<>(clientConfig);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "chat-bus-" + instanceId + "-" + n);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        KafkaConsumer<String, String> consumer;
        try {
            consumer = new KafkaConsumer<>(props);
        } catch (KafkaException e) {
            throw new InfrastructureUnavailableException(COMPONENT, e);
        }
        KafkaSubscription subscription = new KafkaSubscription(name, groupId, workQueue, handler, consumer);
        subscriptions.add(subscription);
        subscription.start();
        if (closed.get()) {
            subscription.close();
            throw closedException();
        }
        if (!subscription.awaitAssignment(ASSIGN_TIMEOUT)) {
            log.warn("Kafka subscription to {} (group {}) has no partition assignment after {}s; it will catch up once assigned",
                    name, groupId, ASSIGN_TIMEOUT.toSeconds());
        }
        return subscription;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            List<KafkaSubscription> active = new ArrayList<>(subscriptions);
            active.forEach(KafkaSubscription::signalStop);
            active.forEach(KafkaSubscription::close);
            try {
                producer.close(CLOSE_TIMEOUT);
            } catch (Exception e) {
                log.warn("Kafka producer close failed: {}", e.getMessage());
            }
            try {
                admin.close(CLOSE_TIMEOUT);
            } catch (Exception e) {
                log.warn("Kafka admin client close failed: {}", e.getMessage());
            }
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw closedException();
        }
    }

    private static InfrastructureUnavailableException closedException() {
        return new InfrastructureUnavailableException(COMPONENT, "closed");
    }

    /** Prefix the topic and replace characters Kafka does not allow in topic names. */
    private String topicName(String topic) {
        String name = (topicPrefix + topic).replaceAll("[^a-zA-Z0-9._-]", "_");
        if (name.length() > 249) {
            throw new IllegalArgumentException("Kafka topic name too long: " + name);
        }
        return name;
    }

    private void ensureTopic(String name) {
        if (!autoCreateTopics || knownTopics.contains(name)) {
            return;
        }
        try {
            admin.createTopics(List.of(new NewTopic(name, partitions, replicationFactor)))
                    .all().get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            log.info("Created Kafka topic {} ({} partitions, replication {})", name, partitions, replicationFactor);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                log.warn("Could not create Kafka topic {}: {}", name, e.getCause().getMessage());
                throw new InfrastructureUnavailableException(COMPONENT, e.getCause());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InfrastructureUnavailableException(COMPONENT, e);
        } catch (TimeoutException e) {
            log.warn("Timed out creating Kafka topic {}", name);
            throw new InfrastructureUnavailableException(COMPONENT, e);
        }
        knownTopics.add(name);
    }

    private final class KafkaSubscription implements Subscription, ConsumerRebalanceListener {

        private final String topic;
        private final String groupId;
        private final boolean workQueue;
        private final Consumer<String> handler;
        private final KafkaConsumer<String, String> consumer;
        private final Thread thread;
        private final CountDownLatch assigned = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final AtomicBoolean stopSignalled = new AtomicBoolean();
        private final AtomicBoolean closeCalled = new AtomicBoolean();
        private volatile boolean running = true;

        KafkaSubscription(String topic, String groupId, boolean workQueue, Consumer<String> handler,
                          KafkaConsumer<String, String> consumer) {
            this.topic = topic;
            this.groupId = groupId;
            this.workQueue = workQueue;
            this.handler = handler;
            this.consumer = consumer;
            this.thread = new Thread(this::run, "kafka-bus-" + groupId);
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        boolean awaitAssignment(Duration timeout) {
            try {
                return assigned.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void run() {
            try {
                consumer.subscribe(List.of(topic), this);
                while (running) {
                    ConsumerRecords<String, String> records;
                    try {
                        records = consumer.poll(POLL_TIMEOUT);
                    } catch (WakeupException e) {
                        continue;
                    } catch (InterruptException e) {
                        break;
                    } catch (Exception e) {
                        if (!running) {
                            break;
                        }
                        log.error("Kafka poll failed on {} (group {}); retrying", topic, groupId, e);
                        pause();
                        continue;
                    }
                    Map<TopicPartition, OffsetAndMetadata> handled = new HashMap<>();
                    for (ConsumerRecord<String, String> record : records) {
                        if (!running) {
                            break; // unhandled records stay uncommitted and go to another group member
                        }
                        dispatch(record);
                        handled.put(new TopicPartition(record.topic(), record.partition()),
                                new OffsetAndMetadata(record.offset() + 1));
                    }
                    commit(handled);
                }
            } catch (Exception e) {
                log.error("Kafka subscription to {} (group {}) stopped unexpectedly", topic, groupId, e);
            } finally {
                try {
                    consumer.close(CLOSE_TIMEOUT);
                } catch (Exception e) {
                    log.warn("Kafka consumer close failed for {}: {}", topic, e.getMessage());
                }
                assigned.countDown();
                stopped.countDown();
            }
        }

        private void dispatch(ConsumerRecord<String, String> record) {
            try {
                handler.accept(record.value());
            } catch (Exception e) {
                log.error("Event handler failed on topic {} (partition {}, offset {})",
                        record.topic(), record.partition(), record.offset(), e);
            }
        }

        private void commit(Map<TopicPartition, OffsetAndMetadata> offsets) {
            if (!workQueue || offsets.isEmpty()) {
                return;
            }
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    consumer.commitSync(offsets);
                    return;
                } catch (WakeupException e) {
                    // close() woke the consumer; the wakeup is consumed, so retry once
                } catch (Exception e) {
                    log.warn("Offset commit failed on {} (group {}), records may be redelivered: {}",
                            topic, groupId, e.getMessage());
                    return;
                }
            }
        }

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            try {
                // Resolve the start offset now: anything published after subscribe() returns is then
                // guaranteed to be seen, even with auto.offset.reset=latest.
                Map<TopicPartition, OffsetAndMetadata> committed = workQueue && !partitions.isEmpty()
                        ? consumer.committed(new HashSet<>(partitions)) : Map.of();
                Map<TopicPartition, OffsetAndMetadata> anchors = new HashMap<>();
                for (TopicPartition partition : partitions) {
                    long position = consumer.position(partition);
                    if (workQueue && committed.get(partition) == null) {
                        anchors.put(partition, new OffsetAndMetadata(position));
                    }
                }
                if (!anchors.isEmpty()) {
                    consumer.commitSync(anchors);
                }
            } catch (WakeupException | InterruptException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Could not resolve start offsets on {} (group {}): {}", topic, groupId, e.getMessage());
            } finally {
                assigned.countDown();
            }
        }

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            // Offsets are committed after every batch, so there is nothing pending to flush here.
        }

        void signalStop() {
            if (stopSignalled.compareAndSet(false, true)) {
                running = false;
                consumer.wakeup();
            }
        }

        @Override
        public void close() {
            if (!closeCalled.compareAndSet(false, true)) {
                return;
            }
            signalStop();
            if (Thread.currentThread() != thread) {
                try {
                    if (!stopped.await(CLOSE_TIMEOUT.toMillis() * 2, TimeUnit.MILLISECONDS)) {
                        log.warn("Kafka subscription to {} did not stop in time", topic);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            subscriptions.remove(this);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(RETRY_BACKOFF.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
