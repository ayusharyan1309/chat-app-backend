package com.ayush.chat.cache;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.ayush.chat.cache.redis.RedisCacheFactory;
import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.connector.Maturity;
import com.ayush.chat.core.error.InfrastructureUnavailableException;
import com.ayush.chat.core.util.Ids;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the cache contract against a real Redis: {@code REDIS_URL}, else {@code redis://localhost:6379};
 * skipped when neither is reachable. Keys live under a random prefix and are removed afterwards.
 */
class RedisCacheStoreTest extends CacheStoreConformanceTest {

    private static final String PREFIX = "chat-test-" + Ids.randomToken(6) + ":";
    private static String redisUrl;
    private static boolean available;

    private final RedisCacheFactory factory = new RedisCacheFactory();

    @BeforeAll
    static void requireRedis() {
        for (String noisy : List.of("io.lettuce", "io.netty", "reactor")) {
            ((Logger) LoggerFactory.getLogger(noisy)).setLevel(Level.WARN);
        }
        String env = System.getenv("REDIS_URL");
        redisUrl = env == null || env.isBlank() ? "redis://localhost:6379" : env.trim();
        available = ping(redisUrl);
    }

    @AfterAll
    static void removeTestKeys() {
        if (available) {
            withRedis(redisUrl, conn -> {
                ScanCursor cursor = ScanCursor.INITIAL;
                do {
                    KeyScanCursor<String> page = conn.sync().scan(cursor, ScanArgs.Builder.matches(PREFIX + "*").limit(500));
                    if (!page.getKeys().isEmpty()) {
                        conn.sync().unlink(page.getKeys().toArray(String[]::new));
                    }
                    cursor = page;
                } while (!cursor.isFinished());
            });
        }
    }

    @Override
    protected CacheStore createCache() {
        // Per-test assumption so surefire reports each test as skipped (a @BeforeAll abort shows as 0 tests).
        Assumptions.assumeTrue(available, "Redis not reachable at " + hostPort(redisUrl) + " (set REDIS_URL)");
        return factory.create(settings(Map.of("uri", redisUrl, "keyPrefix", PREFIX)));
    }

    private ConnectorSettings settings(Map<String, Object> raw) {
        return ConnectorSettings.withDefaults(factory.descriptor(), raw);
    }

    @Test
    void descriptorDeclaresRedisCache() {
        ConnectorDescriptor d = factory.descriptor();
        assertThat(d.kind()).isEqualTo(ConnectorKind.CACHE);
        assertThat(d.id()).isEqualTo("redis");
        assertThat(d.maturity()).isEqualTo(Maturity.GA);
        assertThat(d.compatibleWith()).contains("Valkey", "KeyDB", "Dragonfly", "Amazon ElastiCache",
                "Azure Cache for Redis", "Upstash");
        assertThat(d.field("uri").type()).isEqualTo(ConfigField.FieldType.SECRET);
        assertThat(d.field("uri").required()).isTrue();
        assertThat(d.field("uri").defaultValue()).isEqualTo("redis://localhost:6379");
        assertThat(d.field("keyPrefix").defaultValue()).isEqualTo("chat:");
        assertThat(d.field("timeoutMs").defaultValue()).isEqualTo(3000);
    }

    @Test
    void keysAreWrittenUnderThePrefix() {
        cache.set(key("prefixed"), "v", null);
        withRedis(redisUrl, conn -> assertThat(conn.sync().get(PREFIX + key("prefixed"))).isEqualTo("v"));
    }

    @Test
    void healthPingsAndReportsVersion() {
        HealthStatus health = cache.health();
        assertThat(health.healthy()).isTrue();
        assertThat(health.details()).containsKey("version");
    }

    @Test
    void healthIsDownAndCommandsAreUnavailableAfterClose() throws Exception {
        CacheStore other = createCache();
        other.close();
        assertThat(other.health().healthy()).isFalse();
        assertThatThrownBy(() -> other.get(key("after-close"))).isInstanceOf(InfrastructureUnavailableException.class);
        assertThatThrownBy(() -> other.increment(key("after-close"), 1, null))
                .isInstanceOf(InfrastructureUnavailableException.class);
    }

    @Test
    void scriptsStillWorkAfterScriptCacheFlush() {
        cache.set(key("cas"), "x", null);
        withRedis(redisUrl, conn -> conn.sync().scriptFlush());
        assertThat(cache.deleteIfEquals(key("cas"), "x")).isTrue();
        withRedis(redisUrl, conn -> conn.sync().scriptFlush());
        assertThat(cache.increment(key("n"), 2, Duration.ofMinutes(1))).isEqualTo(2);
    }

    @Test
    void unreachableServerFailsFast() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> factory.create(settings(Map.of("uri", "redis://127.0.0.1:1", "timeoutMs", 1000))))
                .isInstanceOf(RuntimeException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void rejectsNonPositiveTimeout() {
        assertThatThrownBy(() -> factory.create(settings(Map.of("uri", redisUrl, "timeoutMs", 0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static boolean ping(String url) {
        try {
            boolean[] ok = {false};
            withRedis(url, conn -> ok[0] = "PONG".equalsIgnoreCase(conn.sync().ping()));
            return ok[0];
        } catch (Exception e) {
            return false;
        }
    }

    static void withRedis(String url, Consumer<StatefulRedisConnection<String, String>> action) {
        RedisURI uri = RedisURI.create(url);
        uri.setTimeout(Duration.ofSeconds(2));
        RedisClient client = RedisClient.create(uri);
        client.setOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(2)).build()).build());
        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            action.accept(conn);
        } finally {
            client.shutdown(Duration.ZERO, Duration.ofSeconds(1));
        }
    }

    private static String hostPort(String url) {
        try {
            RedisURI uri = RedisURI.create(url);
            return uri.getHost() + ":" + uri.getPort();
        } catch (Exception e) {
            return "<invalid REDIS_URL>";
        }
    }
}
