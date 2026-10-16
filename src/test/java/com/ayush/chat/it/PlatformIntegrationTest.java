package com.ayush.chat.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end: a real server on a random port, driven exactly like a customer would —
 * admin console → tenant + API key → server API → user tokens → REST + STOMP clients.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:control-it;DB_CLOSE_DELAY=-1",
        "platform.connectors.message-store.provider=h2",
        "platform.connectors.message-store.settings.url=jdbc:h2:mem:store-it;DB_CLOSE_DELAY=-1",
        "platform.connectors.blob-store.settings.directory=target/it-media",
        "platform.security.master-key=integration-test-master-key-0123456789",
        "platform.security.allow-private-network-callbacks=true",
        "platform.public-url=http://localhost:0"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformIntegrationTest {

    @LocalServerPort
    int port;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private String adminToken;

    record Response(int status, JsonNode body) {
    }

    // ------------------------------------------------------------------ HTTP helpers

    Response call(String method, String path, Object body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        headers.forEach(builder::header);
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode node = response.body() == null || response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
        return new Response(response.statusCode(), node);
    }

    JsonNode admin(String method, String path, Object body, int expected) throws Exception {
        Response r = call(method, path, body, Map.of("Authorization", "Bearer " + adminToken));
        assertThat(r.status()).as(method + " " + path + " -> " + r.body()).isEqualTo(expected);
        return r.body();
    }

    JsonNode server(String apiKey, String method, String path, Object body, int expected) throws Exception {
        Response r = call(method, path, body, Map.of("X-API-Key", apiKey));
        assertThat(r.status()).as(method + " " + path + " -> " + r.body()).isEqualTo(expected);
        return r.body();
    }

    JsonNode user(String token, String method, String path, Object body, int expected) throws Exception {
        Response r = call(method, path, body, Map.of("Authorization", "Bearer " + token));
        assertThat(r.status()).as(method + " " + path + " -> " + r.body()).isEqualTo(expected);
        return r.body();
    }

    record TenantFixture(String slug, String apiKey) {
    }

    TenantFixture tenant(String plan) throws Exception {
        String name = "Acme " + UUID.randomUUID().toString().substring(0, 6);
        JsonNode created = admin("POST", "/api/admin/tenants",
                Map.of("name", name, "contactEmail", "ops@acme.test", "planCode", plan), 201);
        return new TenantFixture(created.at("/tenant/slug").asText(), created.at("/apiKey/key").asText());
    }

    String userToken(TenantFixture t, String userId) throws Exception {
        server(t.apiKey(), "POST", "/api/v1/server/users", Map.of("id", userId, "displayName", userId.toUpperCase()), 200);
        return server(t.apiKey(), "POST", "/api/v1/server/tokens", Map.of("userId", userId), 200).get("token").asText();
    }

    // ------------------------------------------------------------------ STOMP helper

    class Socket implements AutoCloseable {
        final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        final StompSession session;
        final WebSocketStompClient client;

        Socket(String token) throws Exception {
            client = new WebSocketStompClient(new StandardWebSocketClient());
            client.setMessageConverter(new MappingJackson2MessageConverter());
            StompHeaders connect = new StompHeaders();
            connect.add("Authorization", "Bearer " + token);
            session = client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connect,
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
            Thread.sleep(300);
        }

        void send(String destination, Object body) {
            session.send(destination, body);
        }

        JsonNode await(String type, Predicate<JsonNode> match) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                JsonNode event = events.poll(200, TimeUnit.MILLISECONDS);
                if (event != null && type.equals(event.path("type").asText()) && match.test(event)) {
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

    @BeforeAll
    void login() throws Exception {
        Response r = call("POST", "/api/admin/auth/login", Map.of("email", "admin@local", "password", "admin12345"), Map.of());
        assertThat(r.status()).isEqualTo(200);
        adminToken = r.body().get("token").asText();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void adminEndpointsRequireAuthentication() throws Exception {
        assertThat(call("GET", "/api/admin/tenants", null, Map.of()).status()).isEqualTo(401);
        assertThat(call("GET", "/api/admin/tenants", null, Map.of("Authorization", "Bearer nonsense")).status()).isEqualTo(401);
        assertThat(call("POST", "/api/admin/auth/login", Map.of("email", "admin@local", "password", "wrong"), Map.of()).status())
                .isEqualTo(401);
        JsonNode overview = admin("GET", "/api/admin/system/overview", null, 200);
        assertThat(overview.get("components")).hasSize(5);
        assertThat(overview.at("/components/0/healthy").asBoolean()).isTrue();
        JsonNode catalog = admin("GET", "/api/admin/connectors", null, 200);
        assertThat(catalog.findValuesAsText("id")).contains("postgresql", "mysql", "mongodb", "h2", "memory", "local");
    }

    @Test
    void realtimeDirectChatWithAcksReceiptsTypingAndIdempotency() throws Exception {
        TenantFixture t = tenant("free");
        String alice = userToken(t, "alice");
        String bob = userToken(t, "bob");
        try (Socket aliceWs = new Socket(alice); Socket bobWs = new Socket(bob)) {
            JsonNode conversation = user(alice, "POST", "/api/v1/conversations/direct", Map.of("userId", "bob"), 200);
            String id = conversation.get("id").asText();
            assertThat(conversation.get("title").asText()).isEqualTo("BOB");
            bobWs.await("conversation.updated", e -> e.path("conversationId").asText().equals(id));

            aliceWs.send("/app/messages.send", Map.of("conversationId", id, "clientMsgId", "c-1", "content", "hello bob"));
            JsonNode ack = aliceWs.await("message.ack", e -> true);
            assertThat(ack.at("/data/message/seq").asLong()).isEqualTo(1);
            assertThat(ack.at("/data/duplicate").asBoolean()).isFalse();
            JsonNode received = bobWs.await("message.new", e -> e.at("/data/content").asText().equals("hello bob"));
            assertThat(received.at("/data/senderId").asText()).isEqualTo("alice");

            // retry with the same clientMsgId is a no-op
            aliceWs.send("/app/messages.send", Map.of("conversationId", id, "clientMsgId", "c-1", "content", "hello bob"));
            JsonNode retry = aliceWs.await("message.ack", e -> true);
            assertThat(retry.at("/data/duplicate").asBoolean()).isTrue();
            assertThat(retry.at("/data/message/id").asText()).isEqualTo(ack.at("/data/message/id").asText());

            bobWs.send("/app/typing", Map.of("conversationId", id, "typing", true));
            assertThat(aliceWs.await("typing", e -> true).at("/data/userId").asText()).isEqualTo("bob");

            bobWs.send("/app/receipts", Map.of("conversationId", id, "seq", 1, "kind", "read"));
            JsonNode receipt = aliceWs.await("receipt", e -> e.at("/data/userId").asText().equals("bob"));
            assertThat(receipt.at("/data/readSeq").asLong()).isEqualTo(1);

            JsonNode inbox = user(bob, "GET", "/api/v1/conversations", null, 200);
            assertThat(inbox).hasSize(1);
            assertThat(inbox.at("/0/unreadCount").asLong()).isZero();
            assertThat(inbox.at("/0/lastMessage/preview").asText()).isEqualTo("hello bob");

            user(bob, "POST", "/api/v1/conversations/" + id + "/messages", Map.of("clientMsgId", "b-1", "content", "hi alice"), 201);
            JsonNode history = user(alice, "GET", "/api/v1/conversations/" + id + "/messages", null, 200);
            assertThat(history.findValuesAsText("content")).containsExactly("hello bob", "hi alice");
            JsonNode sync = user(alice, "GET", "/api/v1/conversations/" + id + "/messages?after=1", null, 200);
            assertThat(sync).hasSize(1);
        }
    }

    @Test
    void rapidSendsFromOneSocketKeepSenderOrder() throws Exception {
        TenantFixture t = tenant("enterprise"); // 50 msg/s per user; "pro" would rate-limit a 30-message burst
        String alice = userToken(t, "alice");
        userToken(t, "bob");
        String id = user(alice, "POST", "/api/v1/conversations/direct", Map.of("userId", "bob"), 200).get("id").asText();
        try (Socket ws = new Socket(alice)) {
            for (int i = 0; i < 30; i++) {
                ws.send("/app/messages.send", Map.of("conversationId", id, "clientMsgId", "o-" + i, "content", "#" + i));
            }
            for (int i = 0; i < 30; i++) {
                ws.await("message.ack", e -> true);
            }
        }
        JsonNode history = user(alice, "GET", "/api/v1/conversations/" + id + "/messages?limit=50", null, 200);
        List<String> order = history.findValuesAsText("clientMsgId");
        assertThat(order).hasSize(30);
        for (int i = 0; i < 30; i++) {
            assertThat(order.get(i)).isEqualTo("o-" + i);
            assertThat(history.get(i).get("seq").asLong()).isEqualTo(i + 1);
        }
    }

    @Test
    void clientsCannotInjectEventsIntoOtherUsersQueues() throws Exception {
        TenantFixture a = tenant("free");
        TenantFixture b = tenant("free");
        String victim = userToken(a, "alice");
        String attacker = userToken(b, "mallory");
        try (Socket victimWs = new Socket(victim); Socket attackerWs = new Socket(attacker)) {
            attackerWs.send("/user/" + a.slug() + "::alice/queue/events",
                    Map.of("type", "message.new", "data", Map.of("senderId", "bob", "content", "forged")));
            attackerWs.send("/queue/events", Map.of("type", "message.new"));
            JsonNode event = victimWs.events.poll(1500, TimeUnit.MILLISECONDS);
            assertThat(event).as("victim must not receive injected frames").isNull();
        }
    }

    @Test
    void deactivatedUsersLoseRestAccess() throws Exception {
        TenantFixture t = tenant("free");
        String alice = userToken(t, "alice");
        user(alice, "GET", "/api/v1/me", null, 200);
        server(t.apiKey(), "DELETE", "/api/v1/server/users/alice", null, 204);
        Response r = call("GET", "/api/v1/conversations", null, Map.of("Authorization", "Bearer " + alice));
        assertThat(r.status()).isEqualTo(403);
    }

    @Test
    void activeContentUploadsAreServedAsSandboxedDownloads() throws Exception {
        TenantFixture t = tenant("pro");
        String alice = userToken(t, "alice");
        String boundary = "----svg" + UUID.randomUUID();
        String svg = "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"x.svg\"\r\n"
                + "Content-Type: image/svg+xml\r\n\r\n" + svg + "\r\n--" + boundary + "--\r\n";
        HttpResponse<String> upload = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/media"))
                .header("Authorization", "Bearer " + alice)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(upload.statusCode()).isEqualTo(201);
        URI url = URI.create(json.readTree(upload.body()).get("url").asText());
        HttpResponse<String> download = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + url.getRawPath() + "?" + url.getRawQuery())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.headers().firstValue("Content-Disposition")).hasValue("attachment");
        assertThat(download.headers().firstValue("Content-Type")).hasValue("application/octet-stream");
        assertThat(download.headers().firstValue("Content-Security-Policy").orElse("")).contains("sandbox");
    }

    @Test
    void historyBeforeJoiningAndSystemNoticesAreProtected() throws Exception {
        TenantFixture t = tenant("pro");
        String alice = userToken(t, "alice");
        userToken(t, "bob");
        String carol = userToken(t, "carol");
        String group = user(alice, "POST", "/api/v1/conversations/group", Map.of("title", "Team", "members", List.of("bob")), 201)
                .get("id").asText();
        String secret = user(alice, "POST", "/api/v1/conversations/" + group + "/messages",
                Map.of("content", "before carol joined"), 201).get("id").asText();
        user(alice, "POST", "/api/v1/conversations/" + group + "/members", Map.of("userIds", List.of("carol")), 200);
        user(carol, "PUT", "/api/v1/conversations/" + group + "/messages/" + secret + "/reactions", Map.of("emoji", "👀"), 404);
        user(carol, "POST", "/api/v1/conversations/" + group + "/messages", Map.of("content", "hi", "replyToId", secret), 400);
        JsonNode visible = user(carol, "GET", "/api/v1/conversations/" + group + "/messages", null, 200);
        assertThat(visible.findValuesAsText("content")).doesNotContain("before carol joined");

        String notice = user(alice, "GET", "/api/v1/conversations/" + group + "/messages", null, 200).get(0).get("id").asText();
        user(alice, "PATCH", "/api/v1/conversations/" + group + "/messages/" + notice, Map.of("content", "rewritten"), 403);
        user(alice, "DELETE", "/api/v1/conversations/" + group + "/messages/" + notice, null, 403);
    }

    @Test
    void deletedAdminsLoseAccessImmediately() throws Exception {
        String email = "temp-" + UUID.randomUUID().toString().substring(0, 6) + "@admin.test";
        JsonNode created = admin("POST", "/api/admin/admins",
                Map.of("email", email, "name", "Temp", "password", "temporary-password-1", "role", "ADMIN"), 201);
        String token = call("POST", "/api/admin/auth/login", Map.of("email", email, "password", "temporary-password-1"), Map.of())
                .body().get("token").asText();
        assertThat(call("GET", "/api/admin/tenants", null, Map.of("Authorization", "Bearer " + token)).status()).isEqualTo(200);
        admin("DELETE", "/api/admin/admins/" + created.get("id").asLong(), null, 204);
        assertThat(call("GET", "/api/admin/tenants", null, Map.of("Authorization", "Bearer " + token)).status()).isEqualTo(401);
    }

    @Test
    void featureGatingAndPlanOverrides() throws Exception {
        TenantFixture t = tenant("free");
        String alice = userToken(t, "alice");
        userToken(t, "bob");
        Response denied = call("POST", "/api/v1/conversations/group", Map.of("title", "Team", "members", List.of("bob")),
                Map.of("Authorization", "Bearer " + alice));
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.body().get("error").asText()).isEqualTo("feature_disabled");

        admin("PUT", "/api/admin/tenants/" + t.slug() + "/features", Map.of("overrides", Map.of("group-chat", true)), 200);
        JsonNode group = user(alice, "POST", "/api/v1/conversations/group", Map.of("title", "Team", "members", List.of("bob")), 201);
        assertThat(group.get("role").asText()).isEqualTo("OWNER");
        assertThat(group.get("participants")).hasSize(2);
        JsonNode messages = user(alice, "GET", "/api/v1/conversations/" + group.get("id").asText() + "/messages", null, 200);
        assertThat(messages.at("/0/type").asText()).isEqualTo("SYSTEM");
    }

    @Test
    void tenantsAreIsolated() throws Exception {
        TenantFixture a = tenant("free");
        TenantFixture b = tenant("free");
        String alice = userToken(a, "alice");
        userToken(a, "bob");
        String mallory = userToken(b, "mallory");
        String conversation = user(alice, "POST", "/api/v1/conversations/direct", Map.of("userId", "bob"), 200).get("id").asText();
        user(mallory, "GET", "/api/v1/conversations/" + conversation, null, 404);
        user(mallory, "GET", "/api/v1/conversations/" + conversation + "/messages", null, 404);
        user(mallory, "POST", "/api/v1/conversations/direct", Map.of("userId", "alice"), 404);
        // API keys are tenant-scoped too
        server(b.apiKey(), "GET", "/api/v1/server/users/alice", null, 404);
    }

    @Test
    void suspensionBlocksTheTenant() throws Exception {
        TenantFixture t = tenant("free");
        String alice = userToken(t, "alice");
        Socket socket = new Socket(alice);
        admin("POST", "/api/admin/tenants/" + t.slug() + "/suspend", null, 200);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(socket.session.isConnected()).isFalse());
        socket.client.stop();
        assertThat(call("GET", "/api/v1/me", null, Map.of("Authorization", "Bearer " + alice)).status()).isEqualTo(403);
        Response serverCall = call("POST", "/api/v1/server/tokens", Map.of("userId", "alice"), Map.of("X-API-Key", t.apiKey()));
        assertThat(serverCall.status()).isEqualTo(403);
        assertThat(serverCall.body().get("message").asText()).contains("suspended");
        admin("POST", "/api/admin/tenants/" + t.slug() + "/activate", null, 200);
        user(alice, "GET", "/api/v1/me", null, 200);
    }

    @Test
    void dailyMessageQuotaIsEnforced() throws Exception {
        TenantFixture t = tenant("free");
        admin("PUT", "/api/admin/tenants/" + t.slug(), Map.of("limitOverrides", Map.of("maxMessagesPerDay", 2)), 200);
        String alice = userToken(t, "alice");
        userToken(t, "bob");
        String id = user(alice, "POST", "/api/v1/conversations/direct", Map.of("userId", "bob"), 200).get("id").asText();
        user(alice, "POST", "/api/v1/conversations/" + id + "/messages", Map.of("clientMsgId", "q1", "content", "1"), 201);
        user(alice, "POST", "/api/v1/conversations/" + id + "/messages", Map.of("clientMsgId", "q2", "content", "2"), 201);
        Response blocked = call("POST", "/api/v1/conversations/" + id + "/messages", Map.of("clientMsgId", "q3", "content", "3"),
                Map.of("Authorization", "Bearer " + alice));
        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.body().get("error").asText()).isEqualTo("quota_exceeded");
        // an idempotent retry of an accepted message is still answered
        user(alice, "POST", "/api/v1/conversations/" + id + "/messages", Map.of("clientMsgId", "q2", "content", "2"), 201);
    }

    @Test
    void blockedUsersCannotMessage() throws Exception {
        TenantFixture t = tenant("free");
        String alice = userToken(t, "alice");
        String bob = userToken(t, "bob");
        String id = user(alice, "POST", "/api/v1/conversations/direct", Map.of("userId", "bob"), 200).get("id").asText();
        user(bob, "PUT", "/api/v1/blocks/alice", null, 204);
        Response r = call("POST", "/api/v1/conversations/" + id + "/messages", Map.of("content", "hey"),
                Map.of("Authorization", "Bearer " + alice));
        assertThat(r.status()).isEqualTo(403);
        user(bob, "DELETE", "/api/v1/blocks/alice", null, 204);
        user(alice, "POST", "/api/v1/conversations/" + id + "/messages", Map.of("content", "hey"), 201);
    }

    @Test
    void infrastructureChangesAreValidatedBeforeApplying() throws Exception {
        JsonNode test = admin("POST", "/api/admin/infrastructure/cache/test",
                Map.of("provider", "memory", "settings", Map.of()), 200);
        assertThat(test.get("healthy").asBoolean()).isTrue();
        Response bad = call("PUT", "/api/admin/infrastructure/message-store",
                Map.of("provider", "postgresql", "settings", Map.of("url", "jdbc:postgresql://127.0.0.1:1/nowhere",
                        "connectionTimeoutMs", 1500)),
                Map.of("Authorization", "Bearer " + adminToken));
        assertThat(bad.status()).isEqualTo(400);
        JsonNode state = admin("GET", "/api/admin/infrastructure/message-store", null, 200);
        assertThat(state.get("provider").asText()).isEqualTo("h2");
        assertThat(state.at("/health/healthy").asBoolean()).isTrue();
    }

    @Test
    void dedicatedTenantStoreIsUsed() throws Exception {
        TenantFixture t = tenant("free");
        admin("PUT", "/api/admin/tenants/" + t.slug() + "/connectors/message-store",
                Map.of("provider", "memory", "settings", Map.of()), 200);
        String alice = userToken(t, "alice");
        userToken(t, "bob");
        String id = user(alice, "POST", "/api/v1/conversations/direct", Map.of("userId", "bob"), 200).get("id").asText();
        user(alice, "POST", "/api/v1/conversations/" + id + "/messages", Map.of("content", "stored elsewhere"), 201);
        JsonNode detail = admin("GET", "/api/admin/tenants/" + t.slug(), null, 200);
        assertThat(detail.get("storeProvider").asText()).isEqualTo("memory");
        assertThat(detail.at("/stats/messages").asLong()).isEqualTo(1);
    }

    @Test
    void webhooksAreSignedAndDelivered() throws Exception {
        BlockingQueue<Map<String, String>> received = new LinkedBlockingQueue<>();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(Map.of("body", body,
                    "signature", exchange.getRequestHeaders().getFirst("X-Chat-Signature"),
                    "timestamp", exchange.getRequestHeaders().getFirst("X-Chat-Timestamp")));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
        try {
            TenantFixture t = tenant("pro");
            JsonNode hook = admin("POST", "/api/admin/tenants/" + t.slug() + "/webhooks", Map.of(
                    "url", "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook",
                    "events", List.of("message.created")), 201);
            String secret = hook.get("secret").asText();
            assertThat(secret).startsWith("whsec_");
            String alice = userToken(t, "alice");
            userToken(t, "bob");
            String id = user(alice, "POST", "/api/v1/conversations/direct", Map.of("userId", "bob"), 200).get("id").asText();
            user(alice, "POST", "/api/v1/conversations/" + id + "/messages", Map.of("content", "webhook me"), 201);

            Map<String, String> delivery = received.poll(15, TimeUnit.SECONDS);
            assertThat(delivery).isNotNull();
            JsonNode payload = json.readTree(delivery.get("body"));
            assertThat(payload.get("type").asText()).isEqualTo("message.created");
            assertThat(payload.at("/data/content").asText()).isEqualTo("webhook me");
            assertThat(payload.has("recipients")).isFalse();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal((delivery.get("timestamp") + "." + delivery.get("body"))
                    .getBytes(StandardCharsets.UTF_8)));
            assertThat(delivery.get("signature")).isEqualTo("sha256=" + expected);

            long hookId = hook.get("id").asLong();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                    admin("GET", "/api/admin/tenants/" + t.slug() + "/webhooks/" + hookId + "/deliveries", null, 200)
                            .findValuesAsText("status")).contains("SUCCESS"));
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void mediaUploadAndSignedDownload() throws Exception {
        TenantFixture t = tenant("starter");
        String alice = userToken(t, "alice");
        String boundary = "----test" + UUID.randomUUID();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"pic.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n";
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length() + png.length + tail.length];
        System.arraycopy(head.getBytes(StandardCharsets.UTF_8), 0, body, 0, head.length());
        System.arraycopy(png, 0, body, head.length(), png.length);
        System.arraycopy(tail, 0, body, head.length() + png.length, tail.length);
        HttpResponse<String> upload = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/media"))
                .header("Authorization", "Bearer " + alice)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(upload.statusCode()).isEqualTo(201);
        JsonNode media = json.readTree(upload.body());
        String path = URI.create(media.get("url").asText()).getRawPath() + "?" + URI.create(media.get("url").asText()).getRawQuery();
        HttpResponse<byte[]> download = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(png);
        assertThat(download.headers().firstValue("Content-Type")).hasValue("image/png");
        HttpResponse<byte[]> forged = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + path.replaceAll("sig=[^&]+", "sig=forged"))).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(forged.statusCode()).isEqualTo(404);
    }
}
