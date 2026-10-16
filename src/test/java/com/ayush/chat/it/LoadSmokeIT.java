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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Baseline load probe against a running node: N conversations, each with two users connected over
 * STOMP, sending M messages per sender. Reports send→ack and send→delivery latency percentiles.
 * <pre>LOAD_TEST_URL=http://localhost:18081 LOAD_CONVERSATIONS=25 LOAD_MESSAGES=40 LOAD_INTERVAL_MS=20 mvn test -Dtest=LoadSmokeTest</pre>
 */
@EnabledIfEnvironmentVariable(named = "LOAD_TEST_URL", matches = ".+")
class LoadSmokeIT {

    private final String base = System.getenv("LOAD_TEST_URL");
    private final int conversations = Integer.parseInt(System.getenv().getOrDefault("LOAD_CONVERSATIONS", "25"));
    private final int perSender = Integer.parseInt(System.getenv().getOrDefault("LOAD_MESSAGES", "40"));
    /** Pause between sends per sender; 0 = unpaced burst (measures queueing, not steady-state latency). */
    private final long intervalMs = Long.parseLong(System.getenv().getOrDefault("LOAD_INTERVAL_MS", "0"));
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    private JsonNode post(String path, Object body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        headers.forEach(b::header);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " " + r.body()).isBetween(200, 201);
        return json.readTree(r.body());
    }

    @Test
    void measure() throws Exception {
        String admin = post("/api/admin/auth/login", Map.of("email", "admin@local", "password", "admin12345"), Map.of())
                .get("token").asText();
        JsonNode tenant = post("/api/admin/tenants", Map.of("name", "Load " + UUID.randomUUID().toString().substring(0, 6),
                "planCode", "enterprise"), Map.of("Authorization", "Bearer " + admin));
        Map<String, String> key = Map.of("X-API-Key", tenant.at("/apiKey/key").asText());

        int totalMessages = conversations * 2 * perSender;
        Map<String, Long> sentAt = new ConcurrentHashMap<>();
        List<Long> ackLatency = Collections.synchronizedList(new ArrayList<>());
        List<Long> deliveryLatency = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch acks = new CountDownLatch(totalMessages);
        CountDownLatch deliveries = new CountDownLatch(totalMessages);
        List<WebSocketStompClient> clients = new ArrayList<>();
        List<Runnable> senders = new ArrayList<>();

        for (int c = 0; c < conversations; c++) {
            String a = "u" + c + "a";
            String b = "u" + c + "b";
            post("/api/v1/server/users", Map.of("id", a), key);
            post("/api/v1/server/users", Map.of("id", b), key);
            String tokenA = post("/api/v1/server/tokens", Map.of("userId", a), key).get("token").asText();
            String tokenB = post("/api/v1/server/tokens", Map.of("userId", b), key).get("token").asText();
            String conversation = post("/api/v1/conversations/direct", Map.of("userId", b),
                    Map.of("Authorization", "Bearer " + tokenA)).get("id").asText();
            for (String[] pair : new String[][]{{a, tokenA}, {b, tokenB}}) {
                String me = pair[0];
                WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
                client.setMessageConverter(new MappingJackson2MessageConverter());
                clients.add(client);
                StompHeaders connect = new StompHeaders();
                connect.add("Authorization", "Bearer " + pair[1]);
                StompSession session = client.connectAsync(base.replace("http", "ws") + "/ws", new WebSocketHttpHeaders(),
                        connect, new StompSessionHandlerAdapter() {
                        }).get(10, TimeUnit.SECONDS);
                session.subscribe("/user/queue/events", new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return JsonNode.class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        JsonNode e = (JsonNode) payload;
                        String type = e.path("type").asText();
                        long now = System.nanoTime();
                        if (type.equals("message.ack")) {
                            Long t0 = sentAt.get(e.at("/data/clientMsgId").asText());
                            if (t0 != null) {
                                ackLatency.add(now - t0);
                                acks.countDown();
                            }
                        } else if (type.equals("message.new") && !e.at("/data/senderId").asText().equals(me)) {
                            Long t0 = sentAt.get(e.at("/data/clientMsgId").asText());
                            if (t0 != null) {
                                deliveryLatency.add(now - t0);
                                deliveries.countDown();
                            }
                        }
                    }
                });
                senders.add(() -> {
                    for (int i = 0; i < perSender; i++) {
                        String id = me + "-" + i;
                        sentAt.put(id, System.nanoTime());
                        session.send("/app/messages.send", Map.of("conversationId", conversation, "clientMsgId", id,
                                "content", "load message " + i));
                        if (intervalMs > 0) {
                            try {
                                Thread.sleep(intervalMs);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                });
            }
        }
        Thread.sleep(1000);
        long start = System.nanoTime();
        List<Thread> threads = new ArrayList<>();
        for (Runnable r : senders) {
            threads.add(Thread.ofPlatform().start(r));
        }
        for (Thread t : threads) {
            t.join();
        }
        boolean allAcked = acks.await(120, TimeUnit.SECONDS);
        boolean allDelivered = deliveries.await(120, TimeUnit.SECONDS);
        double seconds = (System.nanoTime() - start) / 1e9;
        clients.forEach(WebSocketStompClient::stop);

        System.out.printf("%n=== Load probe: %d conversations, %d sockets, %d messages in %.2fs → %.0f msg/s (interval %d ms)%n",
                conversations, conversations * 2, totalMessages, seconds, totalMessages / seconds, intervalMs);
        System.out.printf("send→ack      p50 %6.1f ms   p95 %6.1f ms   p99 %6.1f ms%n",
                pct(ackLatency, 50), pct(ackLatency, 95), pct(ackLatency, 99));
        System.out.printf("send→delivery p50 %6.1f ms   p95 %6.1f ms   p99 %6.1f ms%n%n",
                pct(deliveryLatency, 50), pct(deliveryLatency, 95), pct(deliveryLatency, 99));
        assertThat(allAcked).as("all messages acknowledged").isTrue();
        assertThat(allDelivered).as("all messages delivered").isTrue();
    }

    private static double pct(List<Long> values, int p) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        if (sorted.isEmpty()) {
            return Double.NaN;
        }
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1)) / 1e6;
    }
}
