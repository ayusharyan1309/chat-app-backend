package com.ayush.chat.push.webhook;

import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.core.net.OutboundUrlPolicy;
import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorConfig;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorRegistry;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.core.util.Json;
import com.ayush.chat.push.PushSender;
import com.ayush.chat.push.PushSender.PushNotification;
import com.ayush.chat.push.PushSender.PushResult;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookPushSenderTest {

    private record Captured(String method, String path, Headers headers, byte[] body) {
    }

    private final WebhookPushFactory factory = new WebhookPushFactory(new OutboundUrlPolicy(true));
    private final BlockingQueue<Captured> requests = new LinkedBlockingQueue<>();
    private HttpServer server;
    private volatile int status = 200;
    private volatile String responseBody = "";
    private volatile long delayMs;
    private PushSender sender;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            requests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders(), body));
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (sender != null) {
            sender.close();
        }
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private PushSender create(Map<String, Object> raw) {
        return factory.create(ConnectorSettings.withDefaults(factory.descriptor(), raw));
    }

    private static PushNotification notification(Set<String> tokens) {
        return new PushNotification("tenant-1", "user-42", tokens, "New message", "Hi there",
                Map.of("conversationId", "c-1", "seq", "17"));
    }

    @Test
    void descriptorDeclaresWebhookPush() {
        ConnectorDescriptor d = factory.descriptor();
        assertThat(d.kind()).isEqualTo(ConnectorKind.PUSH);
        assertThat(d.id()).isEqualTo("webhook");
        assertThat(d.maturity()).isEqualTo("GA");
        assertThat(d.field("url").required()).isTrue();
        assertThat(d.field("url").type()).isEqualTo(ConfigField.FieldType.URL);
        assertThat(d.field("secret").type()).isEqualTo(ConfigField.FieldType.SECRET);
        assertThat(d.field("timeoutMs").defaultValue()).isEqualTo(5000);
    }

    @Test
    void postsSignedJsonAndCountsDevicesAsDelivered() throws Exception {
        sender = create(Map.of("url", url(), "secret", "s3cr3t"));

        PushResult result = sender.send(notification(Set.of("token-b", "token-a")));

        assertThat(result).isEqualTo(new PushResult(2, 0, Set.of()));
        Captured request = requests.poll(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/hook");
        assertThat(request.headers().getFirst("Content-Type")).isEqualTo("application/json");

        String expectedSignature = "sha256=" + hmacHex("s3cr3t", request.body());
        assertThat(request.headers().getFirst("X-Chat-Signature")).isEqualTo(expectedSignature);

        Map<String, Object> json = Json.readMap(new String(request.body(), StandardCharsets.UTF_8));
        String timestampHeader = request.headers().getFirst("X-Chat-Timestamp");
        assertThat(Long.parseLong(timestampHeader)).isCloseTo(System.currentTimeMillis() / 1000, org.assertj.core.data.Offset.offset(60L));
        assertThat(((Number) json.get("timestamp")).longValue()).isEqualTo(Long.parseLong(timestampHeader));
        assertThat(json).containsEntry("event", "push")
                .containsEntry("tenantId", "tenant-1")
                .containsEntry("userId", "user-42")
                .containsEntry("title", "New message")
                .containsEntry("body", "Hi there")
                .containsEntry("deviceTokens", List.of("token-a", "token-b"))
                .containsEntry("data", Map.of("conversationId", "c-1", "seq", "17"));
    }

    @Test
    void omitsSignatureWithoutSecretAndSaysSoInHealth() throws Exception {
        sender = create(Map.of("url", url()));
        assertThat(sender.health().healthy()).isTrue();
        assertThat(sender.health().message()).contains("unsigned");

        assertThat(sender.send(notification(Set.of("t"))).delivered()).isEqualTo(1);
        Captured request = requests.poll(5, TimeUnit.SECONDS);
        assertThat(request.headers().containsKey("X-Chat-Signature")).isFalse();
        assertThat(request.headers().getFirst("X-Chat-Timestamp")).isNotBlank();
    }

    @Test
    void non2xxCountsAsFailed() {
        sender = create(Map.of("url", url(), "secret", "s"));
        for (int code : List.of(400, 404, 500, 503)) {
            status = code;
            assertThat(sender.send(notification(Set.of("a", "b")))).as("HTTP %d", code)
                    .isEqualTo(new PushResult(0, 2, Set.of()));
        }
        status = 204;
        assertThat(sender.send(notification(Set.of("a", "b")))).isEqualTo(new PushResult(2, 0, Set.of()));
    }

    @Test
    void redirectsAreNotFollowed() {
        sender = create(Map.of("url", url()));
        status = 302;
        assertThat(sender.send(notification(Set.of("a"))).failed()).isEqualTo(1);
    }

    @Test
    void receiverCanReportInvalidTokens() {
        sender = create(Map.of("url", url()));
        responseBody = "{\"delivered\": 1, \"failed\": 2, \"invalidTokens\": [\"dead\", \"never-sent\"]}";

        PushResult result = sender.send(notification(Set.of("live", "dead", "flaky")));

        assertThat(result.delivered()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.invalidTokens()).containsExactly("dead");
    }

    @Test
    void reportWithoutCountsDerivesThemFromInvalidTokens() {
        sender = create(Map.of("url", url()));
        responseBody = "{\"invalidTokens\": [\"dead\"]}";
        assertThat(sender.send(notification(Set.of("live", "dead")))).isEqualTo(new PushResult(1, 1, Set.of("dead")));

        responseBody = "not json";
        assertThat(sender.send(notification(Set.of("live", "dead")))).isEqualTo(new PushResult(2, 0, Set.of()));
    }

    @Test
    void notificationWithoutTokensIsStillPosted() throws Exception {
        sender = create(Map.of("url", url()));
        assertThat(sender.send(notification(Set.of()))).isEqualTo(new PushResult(1, 0, Set.of()));
        Map<String, Object> json = Json.readMap(new String(requests.poll(5, TimeUnit.SECONDS).body(), StandardCharsets.UTF_8));
        assertThat(json).containsEntry("deviceTokens", List.of());
    }

    @Test
    void slowReceiverTimesOut() {
        sender = create(Map.of("url", url(), "timeoutMs", 300));
        delayMs = 3000;
        long start = System.nanoTime();
        assertThat(sender.send(notification(Set.of("a")))).isEqualTo(new PushResult(0, 1, Set.of()));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2500));
    }

    @Test
    void unreachableReceiverCountsAsFailed() {
        sender = create(Map.of("url", "http://127.0.0.1:1/hook", "timeoutMs", 1000));
        assertThat(sender.send(notification(Set.of("a", "b")))).isEqualTo(new PushResult(0, 2, Set.of()));
    }

    @Test
    void healthDoesNotCallTheEndpoint() {
        sender = create(Map.of("url", url(), "secret", "s"));
        assertThat(sender.health().healthy()).isTrue();
        assertThat(sender.health().details()).containsEntry("signed", true);
        assertThat(requests).isEmpty();
    }

    @Test
    void invalidSettingsFailFast() {
        assertThatThrownBy(() -> create(Map.of("url", "ftp://example.com/hook"))).isInstanceOfAny(IllegalArgumentException.class, DomainException.class);
        assertThatThrownBy(() -> create(Map.of("url", "not a url"))).isInstanceOfAny(IllegalArgumentException.class, DomainException.class);
        assertThatThrownBy(() -> create(Map.of("url", "/relative/path"))).isInstanceOfAny(IllegalArgumentException.class, DomainException.class);
        assertThatThrownBy(() -> create(Map.of("url", url(), "timeoutMs", 0))).isInstanceOf(IllegalArgumentException.class);

        ConnectorRegistry registry = new ConnectorRegistry(List.of(factory));
        assertThat(registry.validate(new ConnectorConfig(ConnectorKind.PUSH, "webhook", Map.of())))
                .containsExactly("Endpoint URL is required");
    }

    @Test
    void closeIsIdempotentAndStopsSending() {
        PushSender closing = create(Map.of("url", url()));
        closing.close();
        closing.close();
        assertThat(closing.health().healthy()).isFalse();
        assertThatThrownBy(() -> closing.send(notification(Set.of("a")))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void signMatchesReferenceHmac() throws Exception {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        assertThat(WebhookPushSender.sign("key".getBytes(StandardCharsets.UTF_8), body))
                .isEqualTo("sha256=" + hmacHex("key", body));
    }

    private static String hmacHex(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
