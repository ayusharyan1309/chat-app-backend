package com.ayush.chat.bus.redis;

import com.ayush.chat.bus.EventBus;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorRegistry;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.exception.InfrastructureUnavailableException;
import com.ayush.chat.core.util.Ids;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.UnblockType;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.models.stream.ClaimedMessages;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Redis-backed bus (standalone or Sentinel; not Redis Cluster).
 *
 * <ul>
 *   <li>Broadcast: Pub/Sub on channel {@code <prefix><topic>}. At-most-once: messages published
 *       while a node is disconnected are not replayed.</li>
 *   <li>Work queue: a Redis Stream {@code <prefix><topic>.stream} with one consumer group per
 *       group name; each subscription is its own consumer ({@code <instance>-<n>}). Entries are
 *       XACKed after the handler ran (at-least-once). Entries left pending by a dead consumer are
 *       taken over with XAUTOCLAIM once idle for {@code claimIdle} (default 60s). A new group starts at
 *       the stream tail. The stream is trimmed with {@code MAXLEN ~ streamMaxLen}, so a group that
 *       falls further behind than that loses the oldest entries.</li>
 * </ul>
 * Publishing is one Lua script: PUBLISH, plus XADD only when the stream exists (that is, once a
 * consumer group has been created), so broadcast-only topics never accumulate stream entries.
 *
 * <p>Ordering: Redis delivers Pub/Sub messages and stream entries in the order the server executed
 * the publishes, and each subscription dispatches to its handler from a single thread, so every
 * subscription sees messages in publish order. A group with several members spreads entries across
 * them, so per-key order holds across a group only when it has one member; reclaimed entries are
 * processed late.
 *
 * <p>Failures to publish or subscribe (Redis unreachable, bus closed) surface as
 * {@link InfrastructureUnavailableException} (503). Network I/O is never done while holding a
 * monitor ({@code synchronized}), so virtual threads calling the bus are not pinned.
 */
@Slf4j
public class RedisEventBus implements EventBus {

    private static final String COMPONENT = ConnectorKind.EVENT_BUS.getDisplayName();

    public static final Duration DEFAULT_CLAIM_IDLE = Duration.ofSeconds(60);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration BLOCK = Duration.ofSeconds(1);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);
    private static final int BATCH = 32;

    // KEYS[1] = stream; ARGV: channel, maxlen, payload, key
    private static final String PUBLISH = """
            redis.call('PUBLISH', ARGV[1], ARGV[3])
            if redis.call('EXISTS', KEYS[1]) == 1 then
              redis.call('XADD', KEYS[1], 'MAXLEN', '~', ARGV[2], '*', 'k', ARGV[4], 'p', ARGV[3])
            end
            return 1
            """;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> redis;
    private final String prefix;
    private final long streamMaxLen;
    private final Duration claimIdle;
    private final Duration claimInterval;
    private final String location;
    private final String publishSha;
    private final String instanceId = Ids.randomToken(10).toLowerCase(Locale.ROOT);
    /** Guards {@link #pubSub} and the SUBSCRIBE/UNSUBSCRIBE bookkeeping of {@link #channels}. */
    private final ReentrantLock pubSubLock = new ReentrantLock();
    private StatefulRedisPubSubConnection<String, String> pubSub;
    private final Map<String, List<BroadcastSubscription>> channels = new ConcurrentHashMap<>();
    private final Set<GroupSubscription> groups = ConcurrentHashMap.newKeySet();
    private final AtomicInteger subscriptionSeq = new AtomicInteger();
    private final AtomicLong published = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    public RedisEventBus(String uri, String channelPrefix, long streamMaxLen) {
        this(uri, channelPrefix, streamMaxLen, DEFAULT_CLAIM_IDLE);
    }

    /**
     * @param claimIdle how long a work-queue entry may stay unacknowledged by its consumer before another
     *                  member takes it over; must exceed the slowest handler's run time
     */
    public RedisEventBus(String uri, String channelPrefix, long streamMaxLen, Duration claimIdle) {
        if (claimIdle == null || claimIdle.isZero() || claimIdle.isNegative()) {
            throw new IllegalArgumentException("claimIdle must be positive");
        }
        RedisURI redisUri = RedisURI.create(uri);
        if (RedisURI.DEFAULT_TIMEOUT_DURATION.equals(redisUri.getTimeout())) {
            // Lettuce defaults to 60s; keep publish() from stalling that long during an outage.
            // Must stay above BLOCK, which the dedicated XREADGROUP connections wait on.
            redisUri.setTimeout(COMMAND_TIMEOUT);
        }
        this.location = redisUri.getHost() != null ? redisUri.getHost() + ":" + redisUri.getPort()
                : "sentinel master " + redisUri.getSentinelMasterId();
        this.prefix = channelPrefix == null ? "" : channelPrefix;
        this.streamMaxLen = streamMaxLen;
        this.claimIdle = claimIdle;
        long intervalMs = Math.min(15_000, Math.max(1_000, claimIdle.toMillis() / 4));
        this.claimInterval = Duration.ofMillis(intervalMs);
        this.client = RedisClient.create(redisUri);
        try {
            this.connection = client.connect();
            this.redis = connection.sync();
            this.publishSha = redis.scriptLoad(PUBLISH);
        } catch (RuntimeException e) {
            client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
            throw e;
        }
    }

    @Override
    public String providerId() {
        return "redis";
    }

    @Override
    public HealthStatus health() {
        if (closed.get()) {
            return HealthStatus.down("closed");
        }
        try {
            String pong = redis.ping();
            return "PONG".equalsIgnoreCase(pong)
                    ? HealthStatus.up("Connected to " + location, Map.of(
                    "broadcastSubscriptions", channels.values().stream().mapToInt(List::size).sum(),
                    "groupSubscriptions", groups.size(),
                    "published", published.get()))
                    : HealthStatus.down("Unexpected PING reply: " + pong);
        } catch (Exception e) {
            return HealthStatus.down("Redis unreachable: " + ConnectorRegistry.rootMessage(e));
        }
    }

    @Override
    public void publish(String topic, String key, String payload) {
        ensureOpen();
        String[] keys = {streamKey(topic)};
        String[] args = {channel(topic), String.valueOf(streamMaxLen), payload, key == null ? "" : key};
        try {
            try {
                redis.evalsha(publishSha, ScriptOutputType.INTEGER, keys, args);
            } catch (RedisNoScriptException e) {
                redis.eval(PUBLISH, ScriptOutputType.INTEGER, keys, args);
            }
        } catch (RedisException e) {
            throw new InfrastructureUnavailableException(COMPONENT, e);
        }
        published.incrementAndGet();
    }

    @Override
    public Subscription subscribe(String topic, String group, Consumer<String> handler) {
        ensureOpen();
        Objects.requireNonNull(handler, "handler");
        int n = subscriptionSeq.incrementAndGet();
        Subscription subscription;
        try {
            subscription = group == null
                    ? subscribeBroadcast(channel(topic), handler, n)
                    : subscribeGroup(streamKey(topic), group, handler, n);
        } catch (RedisException e) {
            throw new InfrastructureUnavailableException(COMPONENT, e);
        }
        if (closed.get()) {
            subscription.close();
            throw closedException();
        }
        return subscription;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            List<GroupSubscription> activeGroups = new ArrayList<>(groups);
            activeGroups.forEach(GroupSubscription::signalStop);
            activeGroups.forEach(GroupSubscription::close);
            channels.values().stream().flatMap(List::stream).toList().forEach(BroadcastSubscription::close);
            pubSubLock.lock();
            try {
                if (pubSub != null) {
                    closeQuietly(pubSub);
                    pubSub = null;
                }
            } finally {
                pubSubLock.unlock();
            }
            closeQuietly(connection);
            client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
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

    private String channel(String topic) {
        return prefix + topic;
    }

    private String streamKey(String topic) {
        return prefix + topic + ".stream";
    }

    private static void invoke(Consumer<String> handler, String payload, String source) {
        try {
            handler.accept(payload);
        } catch (Exception e) {
            log.error("Event handler failed on {}", source, e);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // shutting down anyway
        }
    }

    // ---------------------------------------------------------------- broadcast (Pub/Sub)

    private Subscription subscribeBroadcast(String channel, Consumer<String> handler, int n) {
        BroadcastSubscription subscription = new BroadcastSubscription(channel, handler, n);
        pubSubLock.lock();
        try {
            ensureOpen();
            if (pubSub == null) {
                pubSub = client.connectPubSub();
                pubSub.addListener(new RedisPubSubAdapter<>() {
                    @Override
                    public void message(String channel, String message) {
                        // Runs on the Lettuce I/O thread: hand off without blocking.
                        for (BroadcastSubscription sub : channels.getOrDefault(channel, List.of())) {
                            sub.enqueue(message);
                        }
                    }
                });
            }
            List<BroadcastSubscription> subs = channels.computeIfAbsent(channel, c -> new CopyOnWriteArrayList<>());
            subs.add(subscription);
            if (subs.size() == 1) {
                try {
                    // Synchronous: once SUBSCRIBE is acknowledged, later publishes are guaranteed to arrive.
                    pubSub.sync().subscribe(channel);
                } catch (RuntimeException e) {
                    subs.remove(subscription);
                    channels.remove(channel, subs);
                    subscription.dispatcher.shutdownNow();
                    throw e;
                }
            }
        } finally {
            pubSubLock.unlock();
        }
        return subscription;
    }

    private void removeBroadcast(BroadcastSubscription subscription) {
        pubSubLock.lock();
        try {
            List<BroadcastSubscription> subs = channels.get(subscription.channel);
            if (subs != null && subs.remove(subscription) && subs.isEmpty()) {
                channels.remove(subscription.channel);
                if (pubSub != null && pubSub.isOpen() && !closed.get()) {
                    try {
                        pubSub.sync().unsubscribe(subscription.channel);
                    } catch (Exception e) {
                        log.warn("UNSUBSCRIBE {} failed: {}", subscription.channel, e.getMessage());
                    }
                }
            }
        } finally {
            pubSubLock.unlock();
        }
    }

    /** Messages are queued to one thread per subscription, which keeps them in arrival order. */
    private final class BroadcastSubscription implements Subscription {

        private final String channel;
        private final Consumer<String> handler;
        private final ExecutorService dispatcher;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Thread worker;

        BroadcastSubscription(String channel, Consumer<String> handler, int n) {
            this.channel = channel;
            this.handler = handler;
            this.dispatcher = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "redis-bus-" + channel + "-" + n);
                t.setDaemon(true);
                worker = t;
                return t;
            });
        }

        void enqueue(String message) {
            if (closed.get()) {
                return;
            }
            try {
                dispatcher.execute(() -> {
                    if (!closed.get()) {
                        invoke(handler, message, channel);
                    }
                });
            } catch (RejectedExecutionException ignored) {
                // closed concurrently
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                removeBroadcast(this);
                dispatcher.shutdownNow();
                if (Thread.currentThread() != worker) {
                    try {
                        dispatcher.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- work queue (Streams)

    private Subscription subscribeGroup(String stream, String group, Consumer<String> handler, int n) {
        createGroup(redis, stream, group);
        StatefulRedisConnection<String, String> dedicated = client.connect();
        GroupSubscription subscription;
        try {
            subscription = new GroupSubscription(stream, group, instanceId + "-" + n, handler, dedicated);
        } catch (RuntimeException e) {
            closeQuietly(dedicated);
            throw e;
        }
        groups.add(subscription);
        subscription.start();
        return subscription;
    }

    /** Create the consumer group at the stream tail ({@code $}), creating the stream if needed. */
    private static void createGroup(RedisCommands<String, String> commands, String stream, String group) {
        try {
            commands.xgroupCreate(XReadArgs.StreamOffset.latest(stream), group, XGroupCreateArgs.Builder.mkstream());
        } catch (RedisCommandExecutionException e) {
            if (e.getMessage() == null || !e.getMessage().startsWith("BUSYGROUP")) {
                throw e;
            }
        }
    }

    private final class GroupSubscription implements Subscription {

        private final String stream;
        private final String group;
        private final io.lettuce.core.Consumer<String> consumer;
        private final Consumer<String> handler;
        private final StatefulRedisConnection<String, String> conn;
        private final RedisCommands<String, String> commands;
        private final Long clientId;
        private final Thread thread;
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final AtomicBoolean stopSignalled = new AtomicBoolean();
        private final AtomicBoolean closeCalled = new AtomicBoolean();
        private volatile boolean running = true;
        private boolean claimSupported = true;
        private long nextClaimAt;

        GroupSubscription(String stream, String group, String consumerName, Consumer<String> handler,
                          StatefulRedisConnection<String, String> conn) {
            this.stream = stream;
            this.group = group;
            this.consumer = io.lettuce.core.Consumer.from(group, consumerName);
            this.handler = handler;
            this.conn = conn;
            this.commands = conn.sync();
            this.clientId = clientIdOrNull(commands);
            this.thread = new Thread(this::run, "redis-bus-" + stream + "-" + consumerName);
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        private void run() {
            try {
                while (running) {
                    try {
                        claimAbandoned();
                        List<StreamMessage<String, String>> batch = commands.xreadgroup(consumer,
                                XReadArgs.Builder.block(BLOCK).count(BATCH), XReadArgs.StreamOffset.lastConsumed(stream));
                        handle(batch);
                    } catch (RedisCommandExecutionException e) {
                        if (!running) {
                            break;
                        }
                        if (e.getMessage() != null && e.getMessage().startsWith("NOGROUP")) {
                            // Stream key was deleted or evicted: recreate the group and carry on.
                            createGroup(commands, stream, group);
                        } else {
                            log.error("XREADGROUP on {} (group {}) failed; retrying", stream, group, e);
                            pause();
                        }
                    } catch (Exception e) {
                        if (!running) {
                            break;
                        }
                        log.warn("Redis stream read on {} (group {}) failed, retrying: {}", stream, group, e.getMessage());
                        pause();
                    }
                }
            } finally {
                stopped.countDown();
            }
        }

        /**
         * Handles the whole batch even when close() is in progress: entries were already delivered
         * to this consumer, and leaving them pending would delay them until another member claims them.
         * One XACK per batch; a crash mid-batch redelivers the batch (at-least-once).
         */
        private void handle(List<StreamMessage<String, String>> batch) {
            if (batch == null || batch.isEmpty()) {
                return;
            }
            String[] ids = new String[batch.size()];
            for (int i = 0; i < batch.size(); i++) {
                StreamMessage<String, String> message = batch.get(i);
                Map<String, String> body = message.getBody();
                String payload = body == null ? null : body.get("p");
                if (payload != null) { // null body: entry was trimmed while pending
                    invoke(handler, payload, stream);
                }
                ids[i] = message.getId();
            }
            commands.xack(stream, group, ids);
        }

        /** Take over entries another consumer (typically a crashed node) left pending for too long. */
        private void claimAbandoned() {
            long now = System.currentTimeMillis();
            if (!claimSupported || now < nextClaimAt) {
                return;
            }
            nextClaimAt = now + claimInterval.toMillis();
            try {
                String cursor = "0-0";
                do {
                    ClaimedMessages<String, String> claimed = commands.xautoclaim(stream,
                            XAutoClaimArgs.Builder.xautoclaim(consumer, claimIdle, cursor).count(BATCH));
                    if (claimed == null) {
                        return;
                    }
                    if (!claimed.getMessages().isEmpty()) {
                        log.info("Claimed {} abandoned entries on {} (group {})", claimed.getMessages().size(), stream, group);
                    }
                    handle(claimed.getMessages());
                    cursor = claimed.getId();
                } while (running && cursor != null && !"0-0".equals(cursor));
            } catch (RedisCommandExecutionException e) {
                if (e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("unknown command")) {
                    claimSupported = false;
                    log.warn("XAUTOCLAIM not supported by this server (needs Redis 6.2+); abandoned entries on {} will not be reclaimed", stream);
                } else {
                    throw e;
                }
            }
        }

        void signalStop() {
            if (stopSignalled.compareAndSet(false, true)) {
                running = false;
                if (clientId != null) {
                    try {
                        // Wake a blocked XREADGROUP so close() does not wait for the block timeout.
                        redis.clientUnblock(clientId, UnblockType.TIMEOUT);
                    } catch (Exception ignored) {
                        // CLIENT may be restricted on managed services; the read ends within BLOCK anyway
                    }
                }
            }
        }

        @Override
        public void close() {
            if (!closeCalled.compareAndSet(false, true)) {
                return;
            }
            signalStop();
            boolean stoppedCleanly = Thread.currentThread() == thread;
            if (!stoppedCleanly) {
                try {
                    stoppedCleanly = stopped.await(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (stoppedCleanly && Thread.currentThread() != thread) {
                removeConsumerIfIdle();
            }
            closeQuietly(conn);
            groups.remove(this);
        }

        /** Drop this consumer from the group unless it still owns pending entries (those must stay claimable). */
        private void removeConsumerIfIdle() {
            try {
                if (commands.xpending(stream, consumer, Range.create("-", "+"), Limit.from(1)).isEmpty()) {
                    commands.xgroupDelconsumer(stream, consumer);
                }
            } catch (Exception e) {
                log.debug("Could not remove consumer {} from {}: {}", consumer.getName(), stream, e.getMessage());
            }
        }
    }

    private static Long clientIdOrNull(RedisCommands<String, String> commands) {
        try {
            return commands.clientId();
        } catch (Exception e) {
            return null;
        }
    }

    private static void pause() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
