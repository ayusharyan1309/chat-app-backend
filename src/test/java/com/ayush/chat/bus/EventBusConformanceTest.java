package com.ayush.chat.bus;

import com.ayush.chat.core.exception.InfrastructureUnavailableException;
import com.ayush.chat.core.util.Ids;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Behavioural contract for every {@link EventBus}. Distributed providers also supply a peer bus
 * (a second "node" on the same backend) so cross-node fan-out and group sharing are exercised.
 * Every test uses its own topic, so a shared backend is fine.
 */
public abstract class EventBusConformanceTest {

    protected EventBus bus;
    protected String topic;
    private EventBus peer;
    private final List<EventBus> extraBuses = new ArrayList<>();

    protected abstract EventBus createBus() throws Exception;

    /** A second node connected to the same backend; single-node providers return null (the bus is reused). */
    protected EventBus createPeer() throws Exception {
        return null;
    }

    /** Upper bound for a delivery to show up. */
    protected Duration timeout() {
        return Duration.ofSeconds(20);
    }

    /** How long to keep watching before concluding that a message is not coming. */
    protected Duration quietPeriod() {
        return Duration.ofMillis(750);
    }

    @BeforeEach
    void setUpBus() throws Exception {
        bus = createBus();
        topic = "conf-" + Ids.randomToken(8).toLowerCase(Locale.ROOT);
    }

    @AfterEach
    void tearDownBus() {
        extraBuses.forEach(EventBus::close);
        extraBuses.clear();
        if (peer != null && peer != bus) {
            peer.close();
        }
        peer = null;
        if (bus != null) {
            bus.close();
        }
    }

    protected EventBus peer() throws Exception {
        if (peer == null) {
            EventBus created = createPeer();
            peer = created == null ? bus : created;
        }
        return peer;
    }

    @Test
    void reportsHealthyWithProviderId() {
        assertThat(bus.providerId()).isNotBlank();
        assertThat(bus.health().healthy()).isTrue();
    }

    @Test
    void broadcastReachesEverySubscriber() throws Exception {
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        Recorder c = new Recorder();
        bus.subscribe(topic, null, a);
        bus.subscribe(topic, null, b);
        peer().subscribe(topic, null, c);

        bus.publish(topic, "k", "m1");
        bus.publish(topic, "k", "m2");
        peer().publish(topic, "k", "m3");

        for (Recorder r : List.of(a, b, c)) {
            await().atMost(timeout()).untilAsserted(() -> assertThat(r.messages).hasSize(3));
        }
        sleep(quietPeriod());
        assertThat(a.messages).containsExactlyInAnyOrder("m1", "m2", "m3");
        assertThat(b.messages).containsExactlyInAnyOrder("m1", "m2", "m3");
        assertThat(c.messages).containsExactlyInAnyOrder("m1", "m2", "m3");
    }

    @Test
    void broadcastAcceptsNullKey() {
        Recorder r = new Recorder();
        bus.subscribe(topic, null, r);
        bus.publish(topic, null, "no-key");
        await().atMost(timeout()).untilAsserted(() -> assertThat(r.messages).containsExactly("no-key"));
    }

    @Test
    void messagesOnOtherTopicsAreNotDelivered() {
        Recorder r = new Recorder();
        bus.subscribe(topic, null, r);
        bus.publish(topic + "-other", "k", "elsewhere");
        bus.publish(topic, "k", "here");
        await().atMost(timeout()).untilAsserted(() -> assertThat(r.messages).contains("here"));
        sleep(quietPeriod());
        assertThat(r.messages).containsExactly("here");
    }

    @Test
    void groupDeliversEachMessageToExactlyOneMember() throws Exception {
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        bus.subscribe(topic, "workers", first);
        peer().subscribe(topic, "workers", second);

        List<String> sent = IntStream.range(0, 40).mapToObj(i -> "job-" + i).toList();
        for (String message : sent) {
            bus.publish(topic, message, message); // distinct keys spread work across members
        }

        await().atMost(timeout()).untilAsserted(() ->
                assertThat(first.messages.size() + second.messages.size()).isGreaterThanOrEqualTo(sent.size()));
        sleep(quietPeriod());
        List<String> all = new ArrayList<>(first.messages);
        all.addAll(second.messages);
        assertThat(all).containsExactlyInAnyOrderElementsOf(sent);
    }

    @Test
    void everyGroupAndBroadcastSubscriberGetsEachMessage() throws Exception {
        Recorder push = new Recorder();
        Recorder webhooks = new Recorder();
        Recorder fanout = new Recorder();
        bus.subscribe(topic, "push", push);
        peer().subscribe(topic, "webhooks", webhooks);
        bus.subscribe(topic, null, fanout);

        List<String> sent = IntStream.range(0, 10).mapToObj(i -> "event-" + i).toList();
        sent.forEach(m -> bus.publish(topic, m, m));

        for (Recorder r : List.of(push, webhooks, fanout)) {
            await().atMost(timeout()).untilAsserted(() -> assertThat(r.messages).hasSizeGreaterThanOrEqualTo(sent.size()));
        }
        sleep(quietPeriod());
        assertThat(push.messages).containsExactlyInAnyOrderElementsOf(sent);
        assertThat(webhooks.messages).containsExactlyInAnyOrderElementsOf(sent);
        assertThat(fanout.messages).containsExactlyInAnyOrderElementsOf(sent);
    }

    @Test
    void sameKeyIsDeliveredInPublishOrder() {
        Recorder fanout = new Recorder();
        Recorder worker = new Recorder();
        bus.subscribe(topic, null, fanout);
        bus.subscribe(topic, "ordered", worker);

        List<String> keys = List.of("conv-a", "conv-b", "conv-c", "conv-d");
        int perKey = 50;
        for (int seq = 0; seq < perKey; seq++) {
            for (String key : keys) {
                bus.publish(topic, key, key + ":" + seq);
            }
        }

        int total = keys.size() * perKey;
        for (Recorder r : List.of(fanout, worker)) {
            await().atMost(timeout()).untilAsserted(() -> assertThat(r.messages).hasSize(total));
            for (String key : keys) {
                List<Integer> seqs = r.messages.stream().filter(m -> m.startsWith(key + ":"))
                        .map(m -> Integer.parseInt(m.substring(key.length() + 1))).toList();
                assertThat(seqs).as("order for %s", key).containsExactlyElementsOf(IntStream.range(0, perKey).boxed().toList());
            }
        }
    }

    @Test
    void closedSubscriptionStopsReceiving() {
        Recorder closed = new Recorder();
        Recorder open = new Recorder();
        EventBus.Subscription subscription = bus.subscribe(topic, null, closed);
        bus.subscribe(topic, null, open);

        bus.publish(topic, "k", "before");
        await().atMost(timeout()).untilAsserted(() -> {
            assertThat(closed.messages).containsExactly("before");
            assertThat(open.messages).containsExactly("before");
        });

        subscription.close();
        subscription.close(); // idempotent
        bus.publish(topic, "k", "after");
        await().atMost(timeout()).untilAsserted(() -> assertThat(open.messages).containsExactly("before", "after"));
        sleep(quietPeriod());
        assertThat(closed.messages).containsExactly("before");
    }

    @Test
    void closedGroupMemberHandsWorkToRemainingMember() throws Exception {
        Recorder leaving = new Recorder();
        Recorder staying = new Recorder();
        EventBus.Subscription subscription = bus.subscribe(topic, "workers", leaving);
        peer().subscribe(topic, "workers", staying);

        subscription.close();
        List<String> sent = IntStream.range(0, 20).mapToObj(i -> "job-" + i).toList();
        sent.forEach(m -> bus.publish(topic, m, m));

        await().atMost(timeout()).untilAsserted(() -> assertThat(staying.messages).hasSizeGreaterThanOrEqualTo(sent.size()));
        sleep(quietPeriod());
        assertThat(staying.messages).containsExactlyInAnyOrderElementsOf(sent);
        assertThat(leaving.messages).isEmpty();
    }

    @Test
    void handlerFailureDoesNotStopDelivery() {
        List<String> handled = new CopyOnWriteArrayList<>();
        Consumer<String> flaky = m -> {
            if (m.startsWith("boom")) {
                throw new IllegalStateException("handler failure (expected by test)");
            }
            handled.add(m);
        };
        bus.subscribe(topic, null, flaky);
        bus.subscribe(topic, "flaky-workers", flaky);

        bus.publish(topic, "k", "boom-1");
        bus.publish(topic, "k", "ok-1");
        bus.publish(topic, "k", "boom-2");
        bus.publish(topic, "k", "ok-2");

        await().atMost(timeout()).untilAsserted(() ->
                assertThat(handled).containsExactlyInAnyOrder("ok-1", "ok-1", "ok-2", "ok-2"));
    }

    @Test
    void closedBusIsUnavailableAndCloseIsIdempotent() throws Exception {
        EventBus other = createBus();
        extraBuses.add(other);
        other.close();
        other.close();
        assertThatThrownBy(() -> other.publish(topic, "k", "late")).isInstanceOf(InfrastructureUnavailableException.class);
        assertThatThrownBy(() -> other.subscribe(topic, null, new Recorder()))
                .isInstanceOf(InfrastructureUnavailableException.class);
        assertThat(other.health().healthy()).isFalse();
    }

    @Test
    void closingBusStopsItsSubscriptions() throws Exception {
        EventBus node = createPeer();
        if (node == null) {
            return; // single-node provider: covered by closedSubscriptionStopsReceiving
        }
        extraBuses.add(node);
        Recorder onClosedNode = new Recorder();
        Recorder onLiveNode = new Recorder();
        node.subscribe(topic, null, onClosedNode);
        bus.subscribe(topic, null, onLiveNode);

        node.close();
        bus.publish(topic, "k", "after-close");
        await().atMost(timeout()).untilAsserted(() -> assertThat(onLiveNode.messages).containsExactly("after-close"));
        sleep(quietPeriod());
        assertThat(onClosedNode.messages).isEmpty();
    }

    protected static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected static final class Recorder implements Consumer<String> {
        final List<String> messages = new CopyOnWriteArrayList<>();

        @Override
        public void accept(String message) {
            messages.add(message);
        }
    }
}
