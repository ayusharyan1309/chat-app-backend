package com.ayush.chat.bus;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.ayush.chat.bus.redis.RedisEventBus;
import com.ayush.chat.bus.redis.RedisEventBusFactory;
import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.core.util.Ids;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.Consumer;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the bus contract against a real Redis: {@code REDIS_URL}, else {@code redis://localhost:6379};
 * skipped when neither is reachable. Channels and streams use a random prefix; streams are removed afterwards.
 */
class RedisEventBusTest extends EventBusConformanceTest {

    private static final String PREFIX = "chat-test-" + Ids.randomToken(6) + ".";
    private static String redisUrl;
    private static boolean available;

    private final RedisEventBusFactory factory = new RedisEventBusFactory();

    @BeforeAll
    static void requireRedis() {
        for (String noisy : List.of("io.lettuce", "io.netty", "reactor")) {
            ((Logger) LoggerFactory.getLogger(noisy)).setLevel(Level.WARN);
        }
        String env = System.getenv("REDIS_URL");
        redisUrl = env == null || env.isBlank() ? "redis://localhost:6379" : env.trim();
        try {
            available = redis(conn -> "PONG".equalsIgnoreCase(conn.sync().ping()));
        } catch (Exception e) {
            available = false;
        }
    }

    @AfterAll
    static void removeTestStreams() {
        if (!available) {
            return;
        }
        try {
            redis(conn -> {
                ScanCursor cursor = ScanCursor.INITIAL;
                do {
                    KeyScanCursor<String> page = conn.sync().scan(cursor, ScanArgs.Builder.matches(PREFIX + "*").limit(500));
                    if (!page.getKeys().isEmpty()) {
                        conn.sync().unlink(page.getKeys().toArray(String[]::new));
                    }
                    cursor = page;
                } while (!cursor.isFinished());
                return null;
            });
        } catch (Exception ignored) {
            // Redis unavailable: nothing was created
        }
    }

    @Override
    protected EventBus createBus() {
        // Per-test assumption so surefire reports each test as skipped (a @BeforeAll abort shows as 0 tests).
        Assumptions.assumeTrue(available, "Redis not reachable (set REDIS_URL)");
        return factory.create(settings(Map.of("uri", redisUrl, "channelPrefix", PREFIX, "streamMaxLen", 1000)));
    }

    @Override
    protected EventBus createPeer() {
        return createBus();
    }

    private ConnectorSettings settings(Map<String, Object> raw) {
        return ConnectorSettings.withDefaults(factory.descriptor(), raw);
    }

    private String stream() {
        return PREFIX + topic + ".stream";
    }

    @Test
    void descriptorDeclaresRedisBus() {
        ConnectorDescriptor d = factory.descriptor();
        assertThat(d.kind()).isEqualTo(ConnectorKind.EVENT_BUS);
        assertThat(d.id()).isEqualTo("redis");
        assertThat(d.maturity()).isEqualTo("GA");
        assertThat(d.field("uri").type()).isEqualTo(ConfigField.FieldType.SECRET);
        assertThat(d.field("uri").required()).isTrue();
        assertThat(d.field("channelPrefix").defaultValue()).isEqualTo("chat.");
        assertThat(d.field("streamMaxLen").defaultValue()).isEqualTo(100_000);
    }

    @Test
    void broadcastOnlyTopicsDoNotWriteAStream() {
        Recorder r = new Recorder();
        bus.subscribe(topic, null, r);
        bus.publish(topic, "k", "fan-out only");
        await().atMost(timeout()).untilAsserted(() -> assertThat(r.messages).containsExactly("fan-out only"));
        long streams = redis(conn -> conn.sync().exists(stream()));
        assertThat(streams).isZero();
    }

    @Test
    void streamIsTrimmedToApproximatelyMaxLen() {
        // A group without consumers: entries accumulate, so only trimming bounds the stream.
        redis(conn -> conn.sync().xgroupCreate(XReadArgs.StreamOffset.latest(stream()), "idle", XGroupCreateArgs.Builder.mkstream()));
        try (EventBus small = factory.create(settings(Map.of("uri", redisUrl, "channelPrefix", PREFIX, "streamMaxLen", 50)))) {
            for (int i = 0; i < 2000; i++) {
                small.publish(topic, "k", "m" + i);
            }
        }
        // MAXLEN ~ trims whole radix-tree nodes (100 entries by default), so allow some slack.
        long length = redis(conn -> conn.sync().xlen(stream()));
        assertThat(length).isBetween(50L, 250L);
    }

    @Test
    void entriesAbandonedByDeadConsumerAreReclaimed() throws Exception {
        String group = "reclaim";
        redis(conn -> conn.sync().xgroupCreate(XReadArgs.StreamOffset.latest(stream()), group, XGroupCreateArgs.Builder.mkstream()));
        bus.publish(topic, "k", "orphan-1");
        bus.publish(topic, "k", "orphan-2");
        // A consumer reads both entries and dies before acknowledging them.
        redis(conn -> conn.sync().xreadgroup(Consumer.from(group, "crashed-node"), XReadArgs.StreamOffset.lastConsumed(stream())));
        Thread.sleep(600);

        Recorder r = new Recorder();
        try (RedisEventBus survivor = new RedisEventBus(redisUrl, PREFIX, 1000, Duration.ofMillis(500))) {
            survivor.subscribe(topic, group, r);
            await().atMost(timeout()).untilAsserted(() -> assertThat(r.messages).containsExactly("orphan-1", "orphan-2"));
            await().atMost(timeout()).untilAsserted(() -> {
                long pending = redis(conn -> conn.sync().xpending(stream(), group).getCount());
                assertThat(pending).isZero();
            });
        }
    }

    @Test
    void closedGroupSubscriptionLeavesNoIdleConsumerBehind() {
        Recorder r = new Recorder();
        EventBus.Subscription subscription = bus.subscribe(topic, "tidy", r);
        bus.publish(topic, "k", "one");
        await().atMost(timeout()).untilAsserted(() -> assertThat(r.messages).containsExactly("one"));
        subscription.close();
        List<Object> consumers = redis(conn -> conn.sync().xinfoConsumers(stream(), "tidy"));
        assertThat(consumers).isEmpty();
    }

    private static <T> T redis(Function<StatefulRedisConnection<String, String>, T> action) {
        RedisURI uri = RedisURI.create(redisUrl);
        uri.setTimeout(Duration.ofSeconds(2));
        RedisClient client = RedisClient.create(uri);
        client.setOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(2)).build()).build());
        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            return action.apply(conn);
        } finally {
            client.shutdown(Duration.ZERO, Duration.ofSeconds(1));
        }
    }
}
