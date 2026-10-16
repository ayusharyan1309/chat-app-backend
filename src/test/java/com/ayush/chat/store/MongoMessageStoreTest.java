package com.ayush.chat.store;

import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.core.error.ErrorCode;
import com.ayush.chat.core.error.InfrastructureUnavailableException;
import com.ayush.chat.core.util.Ids;
import com.ayush.chat.store.model.AppendResult;
import com.ayush.chat.store.model.ChatMessage;
import com.ayush.chat.store.model.Conversation;
import com.ayush.chat.store.mongo.MongoMessageStoreFactory;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the conformance suite against an in-process mongo-java-server, plus checks that are
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
    void appendToMissingConversationReleasesItsClientIdClaim() {
        ChatMessage orphan = ChatMessage.builder().tenantId(tenant).id(Ids.newId()).conversationId("missing")
                .senderId("alice").clientMsgId("c1").content("x").createdAt(now()).build();
        assertThatThrownBy(() -> store.appendMessage(orphan)).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        // A stale claim would make the retry wait for the claim to expire before failing.
        long start = System.nanoTime();
        assertThatThrownBy(() -> store.appendMessage(orphan.toBuilder().id(Ids.newId()).build()))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
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
    void closeIsIdempotentAndLaterCallsAreUnavailable() {
        store.close();
        store.close();
        assertThatThrownBy(() -> store.findUser(tenant, "alice")).isInstanceOf(InfrastructureUnavailableException.class);
        assertThatThrownBy(() -> direct("alice", "bob")).isInstanceOf(InfrastructureUnavailableException.class);
    }
}
