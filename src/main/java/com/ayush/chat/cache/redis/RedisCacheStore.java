package com.ayush.chat.cache.redis;

import com.ayush.chat.cache.CacheStore;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorRegistry;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.error.InfrastructureUnavailableException;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Redis-backed cache (standalone or Sentinel URIs; works with Valkey, KeyDB, Dragonfly and managed
 * Redis). One multiplexed Lettuce connection is shared by all callers. Compare-and-delete and
 * increment-with-TTL run as Lua scripts so they are atomic across nodes. Commands fail fast while
 * Redis is unreachable, as {@link InfrastructureUnavailableException} (503).
 */
public class RedisCacheStore implements CacheStore {

    private static final String COMPONENT = ConnectorKind.CACHE.getDisplayName();

    private static final String DELETE_IF_EQUALS = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    // ARGV[1] = delta, ARGV[2] = ttl in ms (0 = none). TTL only applies when this call creates the key.
    private static final String INCREMENT = """
            local created = redis.call('EXISTS', KEYS[1]) == 0
            local value = redis.call('INCRBY', KEYS[1], ARGV[1])
            if created and tonumber(ARGV[2]) > 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return value
            """;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> redis;
    private final String keyPrefix;
    private final String location;
    private final String deleteIfEqualsSha;
    private final String incrementSha;
    private final AtomicBoolean closed = new AtomicBoolean();

    public RedisCacheStore(String uri, String keyPrefix, Duration timeout) {
        RedisURI redisUri = RedisURI.create(uri);
        redisUri.setTimeout(timeout);
        this.location = redisUri.getHost() != null ? redisUri.getHost() + ":" + redisUri.getPort()
                : "sentinel master " + redisUri.getSentinelMasterId();
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
        this.client = RedisClient.create(redisUri);
        client.setOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                .timeoutOptions(TimeoutOptions.enabled(timeout))
                // A cache must fail fast while Redis is down rather than queue commands.
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
        try {
            this.connection = client.connect();
            this.redis = connection.sync();
            this.deleteIfEqualsSha = redis.scriptLoad(DELETE_IF_EQUALS);
            this.incrementSha = redis.scriptLoad(INCREMENT);
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
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("server", location);
            details.put("keyPrefix", keyPrefix);
            String version = serverVersion();
            if (version != null) {
                details.put("version", version);
            }
            return "PONG".equalsIgnoreCase(pong)
                    ? HealthStatus.up("Connected to " + location, details)
                    : HealthStatus.down("Unexpected PING reply: " + pong);
        } catch (Exception e) {
            return HealthStatus.down("Redis unreachable: " + ConnectorRegistry.rootMessage(e));
        }
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(command(() -> redis.get(k(key))));
    }

    @Override
    public List<String> getMany(List<String> keys) {
        List<String> values = new ArrayList<>(keys.size());
        if (keys.isEmpty()) {
            return values;
        }
        String[] prefixed = keys.stream().map(this::k).toArray(String[]::new);
        for (KeyValue<String, String> kv : command(() -> redis.mget(prefixed))) {
            values.add(kv.hasValue() ? kv.getValue() : null);
        }
        return values;
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        long ms = ttlMillis(ttl);
        command(() -> ms > 0 ? redis.set(k(key), value, SetArgs.Builder.px(ms)) : redis.set(k(key), value));
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        long ms = ttlMillis(ttl);
        SetArgs args = ms > 0 ? SetArgs.Builder.nx().px(ms) : SetArgs.Builder.nx();
        return "OK".equals(command(() -> redis.set(k(key), value, args)));
    }

    @Override
    public void delete(String key) {
        command(() -> redis.del(k(key)));
    }

    @Override
    public boolean deleteIfEquals(String key, String expected) {
        Long deleted = command(() -> script(deleteIfEqualsSha, DELETE_IF_EQUALS, k(key), expected));
        return deleted != null && deleted > 0;
    }

    @Override
    public long increment(String key, long delta, Duration ttlIfNew) {
        Long value = command(() -> script(incrementSha, INCREMENT, k(key), String.valueOf(delta),
                String.valueOf(ttlMillis(ttlIfNew))));
        return value == null ? 0 : value;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // shutting down anyway
            }
            client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
        }
    }

    private String k(String key) {
        return keyPrefix + key;
    }

    /** Runs one Redis command; an unreachable or failing server (or a closed store) is a 503. */
    private <T> T command(Supplier<T> command) {
        if (closed.get()) {
            throw new InfrastructureUnavailableException(COMPONENT, "connection closed");
        }
        try {
            return command.get();
        } catch (RedisException e) {
            throw new InfrastructureUnavailableException(COMPONENT, e);
        }
    }

    /** EVALSHA with a fallback to EVAL when the script cache was flushed (restart, failover). */
    private Long script(String sha, String source, String key, String... args) {
        String[] keys = {key};
        try {
            return redis.evalsha(sha, ScriptOutputType.INTEGER, keys, args);
        } catch (RedisNoScriptException e) {
            return redis.eval(source, ScriptOutputType.INTEGER, keys, args);
        }
    }

    private String serverVersion() {
        try {
            for (String line : redis.info("server").split("\r?\n")) {
                if (line.startsWith("redis_version:")) {
                    return line.substring("redis_version:".length()).trim();
                }
            }
        } catch (Exception ignored) {
            // INFO may be restricted on some managed services
        }
        return null;
    }

    /** null, zero or negative = no expiry (same as the in-memory cache). */
    private static long ttlMillis(Duration ttl) {
        return ttl == null || ttl.isZero() || ttl.isNegative() ? 0 : Math.max(1, ttl.toMillis());
    }
}
