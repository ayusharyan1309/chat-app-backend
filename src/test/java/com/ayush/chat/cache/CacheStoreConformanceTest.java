package com.ayush.chat.cache;

import com.ayush.chat.core.util.Ids;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Behavioural contract for every {@link CacheStore}. Each provider gets a subclass that supplies a
 * fresh store; every test uses unique keys so a shared backend is fine.
 */
public abstract class CacheStoreConformanceTest {

    protected CacheStore cache;
    private String ns;

    protected abstract CacheStore createCache() throws Exception;

    @BeforeEach
    void setUpCache() throws Exception {
        cache = createCache();
        ns = "it-" + Ids.randomToken(8) + ":";
    }

    @AfterEach
    void tearDownCache() {
        if (cache != null) {
            cache.close();
        }
    }

    protected String key(String name) {
        return ns + name;
    }

    @Test
    void reportsHealthyWithProviderId() {
        assertThat(cache.providerId()).isNotBlank();
        assertThat(cache.health().healthy()).isTrue();
    }

    @Test
    void getMissingKeyIsEmpty() {
        assertThat(cache.get(key("missing"))).isEmpty();
    }

    @Test
    void setThenGetAndOverwrite() {
        cache.set(key("a"), "1", null);
        assertThat(cache.get(key("a"))).contains("1");
        cache.set(key("a"), "2", Duration.ofMinutes(5));
        assertThat(cache.get(key("a"))).contains("2");
    }

    @Test
    void storesUnicodeAndEmptyValues() {
        cache.set(key("u"), "héllo 👋 {\"json\":true}", null);
        cache.set(key("empty"), "", null);
        assertThat(cache.get(key("u"))).contains("héllo 👋 {\"json\":true}");
        assertThat(cache.get(key("empty"))).contains("");
    }

    @Test
    void ttlExpiresKey() {
        cache.set(key("ttl"), "v", Duration.ofMillis(300));
        assertThat(cache.get(key("ttl"))).contains("v");
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                .until(() -> cache.get(key("ttl")).isEmpty());
    }

    @Test
    void nullOrZeroTtlMeansNoExpiry() throws InterruptedException {
        cache.set(key("forever"), "v", null);
        cache.set(key("zero"), "v", Duration.ZERO);
        Thread.sleep(400);
        assertThat(cache.get(key("forever"))).contains("v");
        assertThat(cache.get(key("zero"))).contains("v");
    }

    @Test
    void setWithoutTtlClearsPreviousTtl() throws InterruptedException {
        cache.set(key("k"), "short", Duration.ofMillis(200));
        cache.set(key("k"), "long", null);
        Thread.sleep(500);
        assertThat(cache.get(key("k"))).contains("long");
    }

    @Test
    void getManyKeepsOrderAndMapsMissingToNull() {
        cache.set(key("m1"), "one", null);
        cache.set(key("m3"), "three", null);
        List<String> values = cache.getMany(List.of(key("m1"), key("m2"), key("m3"), key("m1")));
        assertThat(values).containsExactly("one", null, "three", "one");
    }

    @Test
    void getManyOfNothingIsEmpty() {
        assertThat(cache.getMany(List.of())).isEmpty();
    }

    @Test
    void getManySkipsExpiredKeys() {
        cache.set(key("live"), "x", null);
        cache.set(key("dying"), "y", Duration.ofMillis(200));
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertThat(cache.getMany(List.of(key("live"), key("dying")))).containsExactly("x", null));
    }

    @Test
    void setIfAbsentOnlySetsOnce() {
        assertThat(cache.setIfAbsent(key("lock"), "owner-1", Duration.ofMinutes(1))).isTrue();
        assertThat(cache.setIfAbsent(key("lock"), "owner-2", Duration.ofMinutes(1))).isFalse();
        assertThat(cache.get(key("lock"))).contains("owner-1");
        cache.delete(key("lock"));
        assertThat(cache.setIfAbsent(key("lock"), "owner-2", null)).isTrue();
        assertThat(cache.get(key("lock"))).contains("owner-2");
    }

    @Test
    void setIfAbsentSucceedsAgainAfterExpiry() {
        assertThat(cache.setIfAbsent(key("lease"), "a", Duration.ofMillis(200))).isTrue();
        assertThat(cache.setIfAbsent(key("lease"), "b", Duration.ofMillis(200))).isFalse();
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                .until(() -> cache.setIfAbsent(key("lease"), "c", Duration.ofMinutes(1)));
        assertThat(cache.get(key("lease"))).contains("c");
    }

    @Test
    void setIfAbsentHasExactlyOneWinnerUnderContention() throws Exception {
        int threads = 16;
        for (int round = 0; round < 5; round++) {
            String k = key("race-" + round);
            List<Boolean> results = race(threads, i -> cache.setIfAbsent(k, "owner-" + i, Duration.ofMinutes(1)));
            assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
            int winner = results.indexOf(true);
            assertThat(cache.get(k)).contains("owner-" + winner);
        }
    }

    @Test
    void deleteRemovesKeyAndIgnoresMissing() {
        cache.set(key("d"), "v", null);
        cache.delete(key("d"));
        assertThat(cache.get(key("d"))).isEmpty();
        cache.delete(key("never-existed"));
    }

    @Test
    void deleteIfEqualsOnlyDeletesMatchingValue() {
        cache.set(key("lock"), "me", null);
        assertThat(cache.deleteIfEquals(key("lock"), "someone-else")).isFalse();
        assertThat(cache.get(key("lock"))).contains("me");
        assertThat(cache.deleteIfEquals(key("lock"), "me")).isTrue();
        assertThat(cache.get(key("lock"))).isEmpty();
        assertThat(cache.deleteIfEquals(key("lock"), "me")).isFalse();
    }

    @Test
    void deleteIfEqualsOnMissingKeyIsFalse() {
        assertThat(cache.deleteIfEquals(key("absent"), "x")).isFalse();
    }

    @Test
    void deleteIfEqualsHasExactlyOneWinnerUnderContention() throws Exception {
        cache.set(key("cas"), "token", null);
        List<Boolean> results = race(16, i -> cache.deleteIfEquals(key("cas"), "token"));
        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
        assertThat(cache.get(key("cas"))).isEmpty();
    }

    @Test
    void incrementCreatesAddsAndReturnsNewValue() {
        assertThat(cache.increment(key("n"), 5, null)).isEqualTo(5);
        assertThat(cache.increment(key("n"), 3, null)).isEqualTo(8);
        assertThat(cache.increment(key("n"), -10, null)).isEqualTo(-2);
        assertThat(cache.get(key("n"))).contains("-2");
    }

    @Test
    void incrementWorksOnNumericValueWrittenBySet() {
        cache.set(key("num"), "41", null);
        assertThat(cache.increment(key("num"), 1, null)).isEqualTo(42);
    }

    @Test
    void incrementTtlIfNewExpiresTheCounter() {
        assertThat(cache.increment(key("rate"), 1, Duration.ofMillis(300))).isEqualTo(1);
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                .until(() -> cache.get(key("rate")).isEmpty());
        assertThat(cache.increment(key("rate"), 1, null)).as("starts again after expiry").isEqualTo(1);
    }

    @Test
    void incrementDoesNotResetTtlOfExistingCounter() throws InterruptedException {
        cache.increment(key("window"), 1, Duration.ofMillis(400));
        Thread.sleep(150);
        assertThat(cache.increment(key("window"), 1, Duration.ofMinutes(10))).isEqualTo(2);
        // Had the second call extended the TTL to 10 minutes, the key would survive this wait.
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(50))
                .until(() -> cache.get(key("window")).isEmpty());
    }

    @Test
    void incrementDoesNotApplyTtlToExistingKeyWithoutTtl() throws InterruptedException {
        cache.set(key("persistent"), "5", null);
        assertThat(cache.increment(key("persistent"), 1, Duration.ofMillis(100))).isEqualTo(6);
        Thread.sleep(400);
        assertThat(cache.get(key("persistent"))).contains("6");
    }

    @Test
    void incrementWithoutTtlNeverExpires() throws InterruptedException {
        cache.increment(key("total"), 7, null);
        Thread.sleep(300);
        assertThat(cache.get(key("total"))).contains("7");
    }

    @Test
    void concurrentIncrementsAreAtomic() throws Exception {
        int threads = 8;
        int perThread = 100;
        race(threads, i -> {
            for (int n = 0; n < perThread; n++) {
                cache.increment(key("counter"), 1, Duration.ofMinutes(1));
            }
            return true;
        });
        assertThat(cache.get(key("counter"))).contains(String.valueOf(threads * perThread));
    }

    @Test
    void closeIsIdempotent() throws Exception {
        CacheStore other = createCache();
        other.close();
        other.close();
    }

    /** Runs {@code task} on {@code threads} threads released at the same instant; results by thread index. */
    protected static <T> List<T> race(int threads, IndexedTask<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                Callable<T> call = () -> {
                    start.await();
                    return task.run(index);
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            Object[] results = new Object[threads];
            for (int i = 0; i < threads; i++) {
                results[i] = futures.get(i).get(30, TimeUnit.SECONDS);
            }
            @SuppressWarnings("unchecked")
            List<T> list = (List<T>) Arrays.asList(results);
            return list;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    protected interface IndexedTask<T> {
        T run(int index) throws Exception;
    }
}
