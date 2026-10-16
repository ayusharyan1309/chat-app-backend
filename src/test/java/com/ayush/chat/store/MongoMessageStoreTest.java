package com.ayush.chat.store;

import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.core.util.Ids;
import com.ayush.chat.store.model.AppendResult;
import com.ayush.chat.store.model.ChatMessage;
import com.ayush.chat.store.model.Conversation;
import com.ayush.chat.store.model.Participant;
import com.ayush.chat.store.mongo.MongoMessageStoreFactory;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the conformance suite against an in-process mongo-java-server, plus races that are
 * specific to the claim-document design.
 */
class MongoMessageStoreTest extends MessageStoreConformanceTest {

    private static MongoServer server;
    private static String uri;

    @BeforeAll
    static void startServer() {
        server = new MongoServer(new MemoryBackend());
        InetSocketAddress address = server.bind();
        uri = "mongodb://" + address.getHostString() + ":" + address.getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.shutdownNow();
        }
    }

    @Override
    protected MessageStore createStore() throws Exception {
        MongoMessageStoreFactory factory = new MongoMessageStoreFactory();
        return factory.create(ConnectorSettings.withDefaults(factory.descriptor(), Map.of("uri", uri)));
    }

    @Test
    void concurrentRetriesOfOneSendStoreOneMessage() throws Exception {
        Conversation c = direct("alice", "bob");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AppendResult>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                return send(c, "alice", "hi", "retry-me");
            }));
        }
        start.countDown();
        List<AppendResult> results = new ArrayList<>();
        for (Future<AppendResult> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(results).filteredOn(r -> !r.duplicate()).hasSize(1);
        assertThat(results).extracting(r -> r.message().getId()).containsOnly(results.get(0).message().getId());
        assertThat(store.listMessages(tenant, c.getId(), null, null, 50)).hasSize(1);
        assertThat(store.findConversation(tenant, c.getId()).orElseThrow().getLastSeq()).isEqualTo(1);
    }

    @Test
    void concurrentDirectCreationConvergesOnOneConversation() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Conversation>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            boolean flip = t % 2 == 0;
            futures.add(pool.submit(() -> {
                start.await();
                return flip ? direct("alice", "bob") : direct("bob", "alice");
            }));
        }
        start.countDown();
        List<String> ids = new ArrayList<>();
        for (Future<Conversation> f : futures) {
            ids.add(f.get(30, TimeUnit.SECONDS).getId());
        }
        pool.shutdown();
        assertThat(ids).containsOnly(ids.get(0));
        assertThat(store.stats(tenant).conversations()).isEqualTo(1);
        assertThat(store.listParticipants(tenant, ids.get(0))).extracting(Participant::getUserId)
                .containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void appendToMissingConversationFailsAndReleasesClientId() {
        ChatMessage orphan = ChatMessage.builder().tenantId(tenant).id(Ids.newId()).conversationId("missing")
                .senderId("alice").clientMsgId("c1").content("x").createdAt(now()).build();
        assertThatThrownBy(() -> store.appendMessage(orphan)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.appendMessage(orphan.toBuilder().id(Ids.newId()).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idsContainingTheKeySeparatorDoNotCollide() {
        Conversation c = direct("alice", "bob");
        AppendResult first = send(c, "alice", "one", "x|y");
        AppendResult second = send(c, "alice", "two", "x\\|y");
        assertThat(second.duplicate()).isFalse();
        assertThat(second.message().getId()).isNotEqualTo(first.message().getId());
        assertThat(store.findUser(tenant + "|alice", "x")).isEmpty();
    }

    @Test
    void closeIsIdempotent() {
        store.close();
        store.close();
    }
}
