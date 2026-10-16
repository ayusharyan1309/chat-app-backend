package com.ayush.chat.push.fcm;

import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.util.Ids;
import com.ayush.chat.push.PushSender;
import com.google.api.client.http.HttpTransport;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Firebase Cloud Messaging (Android, iOS via APNs, web). Each connector instance owns a uniquely
 * named {@link FirebaseApp}, so several tenants' Firebase projects can be active side by side.
 */
@Slf4j
public class FcmPushSender implements PushSender {

    /** FCM multicast limit. */
    static final int MAX_TOKENS_PER_REQUEST = 500;

    /** Errors meaning the token itself is dead: the caller should forget it. */
    private static final Set<MessagingErrorCode> INVALID_TOKEN_ERRORS = EnumSet.of(
            MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT, MessagingErrorCode.SENDER_ID_MISMATCH);

    private final FirebaseApp app;
    private final FirebaseMessaging messaging;
    private final String projectId;
    private final String clientEmail;
    private final AtomicBoolean closed = new AtomicBoolean();

    public FcmPushSender(String credentialsJson, String projectId) {
        this(credentialsJson, projectId, null);
    }

    /** @param transport HTTP transport for both OAuth and FCM calls (tests); null for the default. */
    FcmPushSender(String credentialsJson, String projectId, HttpTransport transport) {
        if (credentialsJson == null || credentialsJson.isBlank()) {
            throw new IllegalArgumentException("Service account JSON is required");
        }
        GoogleCredentials credentials;
        try (InputStream in = new ByteArrayInputStream(credentialsJson.trim().getBytes(StandardCharsets.UTF_8))) {
            credentials = transport == null ? GoogleCredentials.fromStream(in) : GoogleCredentials.fromStream(in, () -> transport);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Invalid service account JSON: " + e.getMessage(), e);
        }
        ServiceAccountCredentials serviceAccount = credentials instanceof ServiceAccountCredentials sa ? sa : null;
        String resolvedProject = projectId != null && !projectId.isBlank() ? projectId.trim()
                : serviceAccount != null ? serviceAccount.getProjectId() : null;
        if (resolvedProject == null || resolvedProject.isBlank()) {
            throw new IllegalArgumentException("Project id is not in the credentials JSON; set it explicitly");
        }
        this.projectId = resolvedProject;
        this.clientEmail = serviceAccount != null ? serviceAccount.getClientEmail() : null;

        FirebaseOptions.Builder options = FirebaseOptions.builder().setCredentials(credentials).setProjectId(resolvedProject);
        if (transport != null) {
            options.setHttpTransport(transport);
        }
        this.app = FirebaseApp.initializeApp(options.build(), "chat-push-fcm-" + Ids.randomToken(12));
        this.messaging = FirebaseMessaging.getInstance(app);
    }

    @Override
    public String providerId() {
        return "fcm";
    }

    /** Credentials were parsed and the app initialised; no message is sent. */
    @Override
    public HealthStatus health() {
        if (closed.get()) {
            return HealthStatus.down("closed");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("projectId", projectId);
        if (clientEmail != null) {
            details.put("serviceAccount", clientEmail);
        }
        return HealthStatus.up("Credentials loaded for Firebase project " + projectId, details);
    }

    @Override
    public PushResult send(PushNotification notification) {
        if (closed.get()) {
            throw new IllegalStateException("Push sender closed");
        }
        List<String> tokens = notification.deviceTokens() == null ? List.of() : notification.deviceTokens().stream()
                .filter(t -> t != null && !t.isBlank()).distinct().toList();
        if (tokens.isEmpty()) {
            return PushResult.none();
        }
        Map<String, String> data = dataPayload(notification.data());
        int delivered = 0;
        int failed = 0;
        Set<String> invalidTokens = new LinkedHashSet<>();
        for (int from = 0; from < tokens.size(); from += MAX_TOKENS_PER_REQUEST) {
            List<String> chunk = tokens.subList(from, Math.min(from + MAX_TOKENS_PER_REQUEST, tokens.size()));
            try {
                BatchResponse response = messaging.sendEachForMulticast(message(chunk, notification, data));
                List<SendResponse> responses = response.getResponses();
                List<String> dead = new ArrayList<>();
                boolean everyFailureInvalidArgument = chunk.size() > 1;
                for (int i = 0; i < responses.size(); i++) {
                    SendResponse r = responses.get(i);
                    if (r.isSuccessful()) {
                        delivered++;
                        everyFailureInvalidArgument = false;
                        continue;
                    }
                    failed++;
                    MessagingErrorCode code = r.getException() == null ? null : r.getException().getMessagingErrorCode();
                    if (code != MessagingErrorCode.INVALID_ARGUMENT) {
                        everyFailureInvalidArgument = false;
                    }
                    if (code != null && INVALID_TOKEN_ERRORS.contains(code)) {
                        dead.add(chunk.get(i));
                    }
                }
                if (everyFailureInvalidArgument) {
                    // INVALID_ARGUMENT for every token points at the payload, not the tokens: keep them.
                    log.warn("FCM rejected all {} tokens of {}/{} with INVALID_ARGUMENT ({}); tokens kept",
                            chunk.size(), notification.tenantId(), notification.userId(),
                            responses.get(0).getException().getMessage());
                    dead.clear();
                }
                invalidTokens.addAll(dead);
            } catch (FirebaseMessagingException e) {
                failed += chunk.size();
                log.error("FCM send failed for {}/{}: {} {}", notification.tenantId(), notification.userId(),
                        e.getMessagingErrorCode(), e.getMessage());
            }
        }
        return new PushResult(delivered, failed, Set.copyOf(invalidTokens));
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                app.delete();
            } catch (Exception e) {
                log.warn("Deleting Firebase app {} failed: {}", app.getName(), e.getMessage());
            }
        }
    }

    String appName() {
        return app.getName();
    }

    private static MulticastMessage message(List<String> tokens, PushNotification n, Map<String, String> data) {
        MulticastMessage.Builder builder = MulticastMessage.builder()
                .addAllTokens(tokens)
                .putAllData(data)
                .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build());
        if (n.title() != null || n.body() != null) {
            builder.setNotification(Notification.builder().setTitle(n.title()).setBody(n.body()).build())
                    .setApnsConfig(ApnsConfig.builder().setAps(Aps.builder().setSound("default").build()).build());
        }
        return builder.build();
    }

    /** Drops null entries and keys FCM reserves ("from", "message_type", "google.*", "gcm.*"). */
    static Map<String, String> dataPayload(Map<String, String> data) {
        Map<String, String> out = new LinkedHashMap<>();
        if (data == null) {
            return out;
        }
        data.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            String k = key.toLowerCase(Locale.ROOT);
            if (k.equals("from") || k.equals("message_type") || k.startsWith("google") || k.startsWith("gcm")) {
                log.debug("Dropping reserved FCM data key {}", key);
                return;
            }
            out.put(key, value);
        });
        return out;
    }
}
