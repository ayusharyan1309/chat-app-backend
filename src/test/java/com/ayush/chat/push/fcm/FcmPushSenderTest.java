package com.ayush.chat.push.fcm;

import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorConfig;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorRegistry;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.connector.Maturity;
import com.ayush.chat.core.exception.InfrastructureUnavailableException;
import com.ayush.chat.core.util.Json;
import com.ayush.chat.push.PushSender;
import com.ayush.chat.push.PushSender.PushNotification;
import com.ayush.chat.push.PushSender.PushResult;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.firebase.FirebaseApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Settings validation, app lifecycle and result classification. FCM and OAuth are answered by an
 * in-process mock transport, so nothing leaves the machine.
 */
class FcmPushSenderTest {

    private static String serviceAccountJson;

    private final FcmPushFactory factory = new FcmPushFactory();
    private final List<PushSender> senders = new ArrayList<>();

    @BeforeAll
    static void generateServiceAccount() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("type", "service_account");
        json.put("project_id", "demo-project");
        json.put("private_key_id", "test-key-id");
        json.put("private_key", pem);
        json.put("client_email", "push@demo-project.iam.gserviceaccount.com");
        json.put("client_id", "1234567890");
        json.put("token_uri", "https://oauth2.googleapis.com/token");
        serviceAccountJson = Json.write(json);
    }

    @AfterEach
    void closeSenders() {
        senders.forEach(PushSender::close);
    }

    private <T extends PushSender> T track(T sender) {
        senders.add(sender);
        return sender;
    }

    private PushSender create(Map<String, Object> raw) {
        return track(factory.create(ConnectorSettings.withDefaults(factory.descriptor(), raw)));
    }

    private static PushNotification notification(Set<String> tokens, Map<String, String> data) {
        return new PushNotification("tenant-1", "user-42", tokens, "New message", "Hi there", data);
    }

    @Test
    void descriptorDeclaresFcm() {
        ConnectorDescriptor d = factory.descriptor();
        assertThat(d.kind()).isEqualTo(ConnectorKind.PUSH);
        assertThat(d.id()).isEqualTo("fcm");
        assertThat(d.maturity()).isEqualTo(Maturity.GA);
        assertThat(d.field("credentialsJson").type()).isEqualTo(ConfigField.FieldType.SECRET);
        assertThat(d.field("credentialsJson").required()).isTrue();
        assertThat(d.field("projectId").required()).isFalse();
    }

    @Test
    void credentialsAreRequired() {
        ConnectorRegistry registry = new ConnectorRegistry(List.of(factory));
        assertThat(registry.validate(new ConnectorConfig(ConnectorKind.PUSH, "fcm", Map.of())))
                .containsExactly("Service account JSON is required");
    }

    @Test
    void malformedCredentialsFailFast() {
        assertThatThrownBy(() -> create(Map.of("credentialsJson", "{not json")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid service account JSON");
        assertThatThrownBy(() -> create(Map.of("credentialsJson", "{\"type\": \"service_account\", \"project_id\": \"p\"}")))
                .isInstanceOf(IllegalArgumentException.class);
        String noProject = serviceAccountJson.replace("\"project_id\":\"demo-project\",", "");
        assertThatThrownBy(() -> create(Map.of("credentialsJson", noProject)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Project id");
    }

    @Test
    void validCredentialsAreHealthyWithoutNetwork() {
        PushSender sender = create(Map.of("credentialsJson", serviceAccountJson));
        assertThat(sender.providerId()).isEqualTo("fcm");
        HealthStatus health = sender.health();
        assertThat(health.healthy()).isTrue();
        assertThat(health.details()).containsEntry("projectId", "demo-project")
                .containsEntry("serviceAccount", "push@demo-project.iam.gserviceaccount.com");
    }

    @Test
    void projectIdCanBeOverridden() {
        PushSender sender = create(Map.of("credentialsJson", serviceAccountJson, "projectId", "other-project"));
        assertThat(sender.health().details()).containsEntry("projectId", "other-project");
    }

    @Test
    void eachInstanceOwnsAFirebaseAppDeletedOnClose() {
        FcmPushSender first = track(new FcmPushSender(serviceAccountJson, null));
        FcmPushSender second = track(new FcmPushSender(serviceAccountJson, null));
        assertThat(first.appName()).isNotEqualTo(second.appName());
        assertThat(appNames()).contains(first.appName(), second.appName());

        first.close();
        first.close();
        assertThat(appNames()).doesNotContain(first.appName()).contains(second.appName());
        assertThat(first.health().healthy()).isFalse();
        assertThatThrownBy(() -> first.send(notification(Set.of("t"), Map.of())))
                .isInstanceOf(InfrastructureUnavailableException.class);
    }

    @Test
    void noTokensMeansNothingToSend() {
        PushSender sender = create(Map.of("credentialsJson", serviceAccountJson));
        assertThat(sender.send(notification(Set.of(), Map.of()))).isEqualTo(PushResult.none());
        assertThat(sender.send(notification(null, null))).isEqualTo(PushResult.none());
    }

    @Test
    void classifiesPerTokenResultsAndReportsDeadTokens() {
        FakeFcm fcm = new FakeFcm(token -> switch (token) {
            case "dead-unregistered" -> error(404, "NOT_FOUND", "UNREGISTERED");
            case "dead-invalid" -> error(400, "INVALID_ARGUMENT", "INVALID_ARGUMENT");
            case "dead-sender" -> error(403, "PERMISSION_DENIED", "SENDER_ID_MISMATCH");
            case "throttled" -> error(429, "RESOURCE_EXHAUSTED", "QUOTA_EXCEEDED");
            default -> ok();
        });
        FcmPushSender sender = track(new FcmPushSender(serviceAccountJson, null, fcm));

        PushResult result = sender.send(notification(
                Set.of("ok-1", "ok-2", "dead-unregistered", "dead-invalid", "dead-sender", "throttled"),
                Map.of("conversationId", "c-1", "from", "reserved", "google.sent_time", "1")));

        assertThat(result.delivered()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(4);
        assertThat(result.invalidTokens()).containsExactlyInAnyOrder("dead-unregistered", "dead-invalid", "dead-sender");

        Map<String, Object> message = fcm.messages.stream().filter(m -> "ok-1".equals(m.get("token"))).findFirst().orElseThrow();
        assertThat(message.get("notification")).isEqualTo(Map.of("title", "New message", "body", "Hi there"));
        assertThat(message.get("data")).isEqualTo(Map.of("conversationId", "c-1"));
        assertThat(message.get("android")).isEqualTo(Map.of("priority", "high"));
        assertThat(fcm.oauthCalls).isPositive();
    }

    @Test
    void invalidArgumentForEveryTokenIsTreatedAsPayloadProblem() {
        FakeFcm fcm = new FakeFcm(token -> error(400, "INVALID_ARGUMENT", "INVALID_ARGUMENT"));
        FcmPushSender sender = track(new FcmPushSender(serviceAccountJson, null, fcm));

        PushResult result = sender.send(notification(Set.of("a", "b", "c"), Map.of()));

        assertThat(result).isEqualTo(new PushResult(0, 3, Set.of()));
    }

    @Test
    void sendsMoreThanOneMulticastBatch() {
        FakeFcm fcm = new FakeFcm(token -> ok());
        FcmPushSender sender = track(new FcmPushSender(serviceAccountJson, null, fcm));
        Set<String> tokens = IntStream.range(0, 1_201).mapToObj(i -> "token-" + i).collect(Collectors.toSet());

        PushResult result = sender.send(notification(tokens, Map.of()));

        assertThat(result).isEqualTo(new PushResult(1_201, 0, Set.of()));
        assertThat(fcm.messages).hasSize(1_201);
    }

    @Test
    void dataPayloadDropsReservedKeysAndNulls() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("conversationId", "c-1");
        data.put("message_type", "x");
        data.put("GCM.notification", "x");
        data.put("nullValue", null);
        assertThat(FcmPushSender.dataPayload(data)).containsExactly(Map.entry("conversationId", "c-1"));
        assertThat(FcmPushSender.dataPayload(null)).isEmpty();
    }

    private static Set<String> appNames() {
        return FirebaseApp.getApps().stream().map(FirebaseApp::getName).collect(Collectors.toSet());
    }

    private static MockLowLevelHttpResponse ok() {
        return json(200, "{\"name\": \"projects/demo-project/messages/1\"}");
    }

    private static MockLowLevelHttpResponse error(int status, String grpcStatus, String fcmErrorCode) {
        return json(status, "{\"error\": {\"code\": " + status + ", \"message\": \"test\", \"status\": \"" + grpcStatus + "\","
                + " \"details\": [{\"@type\": \"type.googleapis.com/google.firebase.fcm.v1.FcmError\","
                + " \"errorCode\": \"" + fcmErrorCode + "\"}]}}");
    }

    private static MockLowLevelHttpResponse json(int status, String body) {
        return new MockLowLevelHttpResponse().setStatusCode(status).setContentType("application/json").setContent(body);
    }

    /** Answers OAuth token requests and FCM v1 sends; the responder decides each token's fate. */
    private static final class FakeFcm extends MockHttpTransport {
        final List<Map<String, Object>> messages = new CopyOnWriteArrayList<>();
        volatile int oauthCalls;
        private final Function<String, MockLowLevelHttpResponse> responder;

        FakeFcm(Function<String, MockLowLevelHttpResponse> responder) {
            this.responder = responder;
        }

        @Override
        public LowLevelHttpRequest buildRequest(String method, String url) {
            return new MockLowLevelHttpRequest(url) {
                @Override
                @SuppressWarnings("unchecked")
                public LowLevelHttpResponse execute() throws IOException {
                    if (url.startsWith("https://oauth2.googleapis.com/")) {
                        oauthCalls++;
                        return json(200, "{\"access_token\": \"test-token\", \"expires_in\": 3600, \"token_type\": \"Bearer\"}");
                    }
                    assertThat(url).isEqualTo("https://fcm.googleapis.com/v1/projects/demo-project/messages:send");
                    Map<String, Object> message = (Map<String, Object>) Json.readMap(getContentAsString()).get("message");
                    messages.add(message);
                    return responder.apply((String) message.get("token"));
                }
            };
        }
    }
}
