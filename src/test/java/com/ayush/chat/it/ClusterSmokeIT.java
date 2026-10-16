package com.ayush.chat.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Multi-node smoke test against two externally started nodes that share a control plane,
 * message store, cache and event bus. Run with:
 * <pre>CLUSTER_NODE_A=http://localhost:18081 CLUSTER_NODE_B=http://localhost:18082 mvn test -Dtest=ClusterSmokeTest</pre>
 */
@EnabledIfEnvironmentVariable(named = "CLUSTER_NODE_A", matches = ".+")
class ClusterSmokeIT {

    private final String nodeA = System.getenv("CLUSTER_NODE_A");
    private final String nodeB = System.getenv("CLUSTER_NODE_B");
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    JsonNode call(String base, String method, String path, Object body, Map<String, String> headers, int expected) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        headers.forEach(builder::header);
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(method + " " + base + path + " -> " + response.body()).isEqualTo(expected);
        return response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
    }

    static final class Socket implements AutoCloseable {
        final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        final StompSession session;
        final WebSocketStompClient client;

        Socket(String base, String token) throws Exception {
            client = new WebSocketStompClient(new StandardWebSocketClient());
            client.setMessageConverter(new MappingJackson2MessageConverter());
            StompHeaders connect = new StompHeaders();
            connect.add("Authorization", "Bearer " + token);
            session = client.connectAsync(base.replace("http", "ws") + "/ws", new WebSocketHttpHeaders(), connect,
                    new StompSessionHandlerAdapter() {
                    }).get(10, TimeUnit.SECONDS);
            session.subscribe("/user/queue/events", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return JsonNode.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    events.add((JsonNode) payload);
                }
            });
            Thread.sleep(500);
        }

        JsonNode await(String type) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                JsonNode event = events.poll(200, TimeUnit.MILLISECONDS);
                if (event != null && type.equals(event.path("type").asText())) {
                    return event;
                }
            }
            throw new AssertionError("Timed out waiting for " + type);
        }

        @Override
        public void close() {
            if (session.isConnected()) {
                session.disconnect();
            }
            client.stop();
        }
    }

    @Test
    void messagesReceiptsPresenceAndAdminChangesCrossNodes() throws Exception {
        String admin = call(nodeA, "POST", "/api/admin/auth/login",
                Map.of("email", "admin@local", "password", "admin12345"), Map.of(), 200).get("token").asText();
        Map<String, String> adminAuth = Map.of("Authorization", "Bearer " + admin);
        JsonNode created = call(nodeA, "POST", "/api/admin/tenants",
                Map.of("name", "Cluster " + UUID.randomUUID().toString().substring(0, 6), "planCode", "pro"), adminAuth, 201);
        String slug = created.at("/tenant/slug").asText();
        Map<String, String> key = Map.of("X-API-Key", created.at("/apiKey/key").asText());

        // the API key created on node A works on node B (shared control plane)
        call(nodeB, "POST", "/api/v1/server/users", Map.of("id", "alice", "displayName", "Alice"), key, 200);
        call(nodeB, "POST", "/api/v1/server/users", Map.of("id", "bob", "displayName", "Bob"), key, 200);
        String alice = call(nodeA, "POST", "/api/v1/server/tokens", Map.of("userId", "alice"), key, 200).get("token").asText();
        String bob = call(nodeB, "POST", "/api/v1/server/tokens", Map.of("userId", "bob"), key, 200).get("token").asText();

        try (Socket aliceOnA = new Socket(nodeA, alice); Socket bobOnB = new Socket(nodeB, bob)) {
            String conversation = call(nodeA, "POST", "/api/v1/conversations/direct", Map.of("userId", "bob"),
                    Map.of("Authorization", "Bearer " + alice), 200).get("id").asText();
            assertThat(bobOnB.await("conversation.updated").path("conversationId").asText()).isEqualTo(conversation);

            aliceOnA.session.send("/app/presence.watch", Map.of("userIds", List.of("bob")));
            assertThat(aliceOnA.await("presence").at("/data/online").asBoolean()).isTrue();

            aliceOnA.session.send("/app/messages.send", Map.of("conversationId", conversation, "clientMsgId", "x1",
                    "content", "hello from node A"));
            assertThat(aliceOnA.await("message.ack").at("/data/message/seq").asLong()).isEqualTo(1);
            assertThat(bobOnB.await("message.new").at("/data/content").asText()).isEqualTo("hello from node A");

            bobOnB.session.send("/app/receipts", Map.of("conversationId", conversation, "seq", 1, "kind", "read"));
            assertThat(aliceOnA.await("receipt").at("/data/readSeq").asLong()).isEqualTo(1);

            // node B's connection counts reach node A with its next heartbeat (every 10 s)
            await().atMost(Duration.ofSeconds(25)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
                JsonNode overview = call(nodeA, "GET", "/api/admin/system/overview", null, adminAuth, 200);
                assertThat(overview.get("nodes").size()).isGreaterThanOrEqualTo(2);
                assertThat(overview.at("/totals/connections").asInt()).isGreaterThanOrEqualTo(2);
            });

            // suspension on node A closes Bob's socket on node B and blocks node B's API
            call(nodeA, "POST", "/api/admin/tenants/" + slug + "/suspend", null, adminAuth, 200);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(bobOnB.session.isConnected()).isFalse());
            call(nodeB, "GET", "/api/v1/me", null, Map.of("Authorization", "Bearer " + bob), 403);
        }
    }
}
