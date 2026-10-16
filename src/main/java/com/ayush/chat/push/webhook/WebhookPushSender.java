package com.ayush.chat.push.webhook;

import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.crypto.WebhookSignature;
import com.ayush.chat.core.exception.InfrastructureUnavailableException;
import com.ayush.chat.core.util.Json;
import com.ayush.chat.push.PushSender;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * Hands each notification to the tenant's own push service (APNs, OneSignal, ...) as an HTTP POST.
 *
 * <p>Body: JSON {@code {event, timestamp, tenantId, userId, deviceTokens, title, body, data}}.
 * Headers follow the platform's single callback signature scheme ({@link WebhookSignature}, the
 * same as tenant webhooks): {@code X-Chat-Timestamp} (epoch seconds, equal to {@code timestamp} in
 * the body) and, when a secret is configured,
 * {@code X-Chat-Signature: sha256=<hex HMAC-SHA256(secret, timestamp + "." + body)>}, so receivers
 * can reject stale replays.
 *
 * <p>Any 2xx response counts as delivered. A 2xx JSON response may report
 * {@code {"invalidTokens": [...], "delivered": n, "failed": n}}; reported tokens are passed back so
 * the caller can forget them. Other statuses and network errors count as failed (no retries here).
 */
@Slf4j
public class WebhookPushSender implements PushSender {

    private static final String COMPONENT = ConnectorKind.PUSH.getDisplayName();
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    record WebhookPayload(String event, long timestamp, String tenantId, String userId, Collection<String> deviceTokens,
                          String title, String body, Map<String, String> data) {
    }

    private final URI url;
    private final String secret;
    private final Duration timeout;
    private final HttpClient http;
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Re-validates the endpoint before every send (DNS can change after the connector was saved). */
    private final UnaryOperator<URI> urlGuard;

    public WebhookPushSender(String url, String secret, Duration timeout) {
        this(url, secret, timeout, UnaryOperator.identity());
    }

    public WebhookPushSender(String url, String secret, Duration timeout, UnaryOperator<URI> urlGuard) {
        this.urlGuard = urlGuard;
        this.url = parseUrl(url);
        this.secret = secret == null || secret.isBlank() ? null : secret;
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String providerId() {
        return "webhook";
    }

    /** Settings sanity only: the endpoint is not called. */
    @Override
    public HealthStatus health() {
        if (closed.get()) {
            return HealthStatus.down("closed");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("url", url.getScheme() + "://" + url.getAuthority() + (url.getRawPath() == null ? "" : url.getRawPath()));
        details.put("signed", secret != null);
        details.put("delivered", delivered.get());
        details.put("failed", failed.get());
        StringBuilder message = new StringBuilder("Webhook configured");
        if (secret == null) {
            message.append(" (unsigned: set a secret so the receiver can verify requests)");
        }
        if ("http".equals(url.getScheme())) {
            message.append(" (plain HTTP)");
        }
        return HealthStatus.up(message.toString(), details);
    }

    @Override
    public PushResult send(PushNotification notification) {
        if (closed.get()) {
            throw new InfrastructureUnavailableException(COMPONENT, "closed");
        }
        Set<String> tokens = new TreeSet<>();
        if (notification.deviceTokens() != null) {
            notification.deviceTokens().stream().filter(t -> t != null && !t.isBlank()).forEach(tokens::add);
        }
        // Without device tokens the receiver may still resolve devices from userId: count the notification once.
        int targets = Math.max(1, tokens.size());
        long timestamp = Instant.now().getEpochSecond();
        String body = Json.write(new WebhookPayload("push", timestamp, notification.tenantId(), notification.userId(),
                tokens, notification.title(), notification.body(), notification.data()));

        URI target;
        try {
            target = urlGuard.apply(url);
        } catch (RuntimeException rejected) {
            log.warn("Push webhook {} rejected by the outbound URL policy: {}", url.getHost(), rejected.getMessage());
            return record(new PushResult(0, targets, Set.of()));
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header(WebhookSignature.TIMESTAMP_HEADER, String.valueOf(timestamp))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (secret != null) {
            request.header(WebhookSignature.SIGNATURE_HEADER, WebhookSignature.sign(secret, timestamp, body));
        }
        try {
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            String responseBody;
            try (InputStream in = response.body()) {
                responseBody = new String(in.readNBytes(MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
            }
            if (response.statusCode() / 100 == 2) {
                return record(fromResponse(responseBody, targets, tokens));
            }
            log.warn("Push webhook {} answered HTTP {} for {}/{}", url.getHost(), response.statusCode(),
                    notification.tenantId(), notification.userId());
        } catch (IOException e) {
            log.warn("Push webhook {} failed for {}/{}: {}", url.getHost(), notification.tenantId(), notification.userId(),
                    e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return record(new PushResult(0, targets, Set.of()));
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            http.shutdown();
            try {
                if (!http.awaitTermination(timeout)) {
                    http.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                http.shutdownNow();
            }
        }
    }

    private PushResult record(PushResult result) {
        delivered.addAndGet(result.delivered());
        failed.addAndGet(result.failed());
        return result;
    }

    /** Optional receiver report; only tokens that were actually sent may be reported invalid. */
    static PushResult fromResponse(String body, int targets, Set<String> sentTokens) {
        if (body == null || !body.trim().startsWith("{")) {
            return new PushResult(targets, 0, Set.of());
        }
        try {
            Map<String, Object> report = Json.readMap(body);
            Set<String> invalid = new LinkedHashSet<>();
            if (report.get("invalidTokens") instanceof List<?> list) {
                for (Object token : list) {
                    if (token instanceof String s && sentTokens.contains(s)) {
                        invalid.add(s);
                    }
                }
            }
            int failedCount = report.get("failed") instanceof Number n ? n.intValue() : invalid.size();
            int deliveredCount = report.get("delivered") instanceof Number n ? n.intValue() : Math.max(0, targets - failedCount);
            return new PushResult(deliveredCount, failedCount, Set.copyOf(invalid));
        } catch (Exception e) {
            return new PushResult(targets, 0, Set.of()); // 2xx with an unparseable body still means accepted
        }
    }

    static URI parseUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Webhook URL is required");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid webhook URL: " + url, e);
        }
        if (uri.getScheme() == null || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("Webhook URL must be an absolute http(s) URL");
        }
        return uri;
    }
}
