package com.ayush.chat.chat.webhook;

import com.ayush.chat.chat.RealtimeGateway;
import com.ayush.chat.chat.event.DomainEvent;
import com.ayush.chat.core.config.ConnectorConfigService;
import com.ayush.chat.core.crypto.SecretCipher;
import com.ayush.chat.core.error.ApiException;
import com.ayush.chat.core.net.OutboundUrlPolicy;
import com.ayush.chat.core.util.Ids;
import com.ayush.chat.core.util.Json;
import com.ayush.chat.tenant.Feature;
import com.ayush.chat.tenant.Tenant;
import com.ayush.chat.tenant.TenantDirectory;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tenant webhooks: management plus at-least-once delivery of domain events with HMAC signatures
 * and exponential-backoff retries. Consumes the event bus as a work queue, so each event is
 * dispatched by exactly one node.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private static final int MAX_ATTEMPTS = 5;

    private final WebhookRepository webhooks;
    private final DeliveryRepository deliveries;
    private final SecretCipher cipher;
    private final TenantDirectory tenants;
    private final RealtimeGateway gateway;
    private final OutboundUrlPolicy urlPolicy;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "webhook-dispatch");
        t.setDaemon(true);
        return t;
    });
    private final Cache<String, List<Target>> targets = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30)).maximumSize(10_000).build();

    public interface WebhookRepository extends JpaRepository<Webhook, Long> {
        @Query("select w from Webhook w where w.tenant.id = :tenantId order by w.id")
        List<Webhook> findByTenantId(@Param("tenantId") Long tenantId);

        @Query("select w from Webhook w join w.tenant t where t.slug = :slug and w.active = true")
        List<Webhook> findActiveBySlug(@Param("slug") String slug);
    }

    public interface DeliveryRepository extends JpaRepository<WebhookDelivery, Long> {
        @Query("select d from WebhookDelivery d where d.webhookId = :webhookId order by d.id desc")
        List<WebhookDelivery> recent(@Param("webhookId") Long webhookId, PageRequest page);

        @Modifying
        @Query("delete from WebhookDelivery d where d.createdAt < :before")
        int purgeBefore(@Param("before") Instant before);
    }

    public record WebhookInput(String url, List<String> events, String description, Boolean active) {
    }

    /** {@code secret} is plaintext only right after creation; otherwise masked. */
    public record WebhookView(Long id, String url, Set<String> events, String description, boolean active, String secret,
                              Instant createdAt, Instant updatedAt) {
    }

    private record Target(Long id, String url, String secret, Set<String> events) {
    }

    @PostConstruct
    void subscribe() {
        gateway.subscribeDomainEvents("webhooks", this::onEvent);
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ management

    @Transactional(readOnly = true)
    public List<WebhookView> list(Tenant tenant) {
        return webhooks.findByTenantId(tenant.getId()).stream().map(w -> view(w, null)).toList();
    }

    @Transactional
    public WebhookView create(Tenant tenant, WebhookInput input) {
        tenants.get(tenant.getSlug()).require(Feature.WEBHOOKS);
        Webhook webhook = new Webhook();
        webhook.setTenant(tenant);
        apply(webhook, input);
        String secret = "whsec_" + Ids.randomToken(32);
        webhook.setSecret(cipher.encrypt(secret));
        webhooks.save(webhook);
        targetsChanged(tenant.getSlug());
        return view(webhook, secret);
    }

    @Transactional
    public WebhookView update(Tenant tenant, Long id, WebhookInput input) {
        Webhook webhook = owned(tenant, id);
        apply(webhook, input);
        webhooks.save(webhook);
        targetsChanged(tenant.getSlug());
        return view(webhook, null);
    }

    @Transactional
    public void delete(Tenant tenant, Long id) {
        webhooks.delete(owned(tenant, id));
        targetsChanged(tenant.getSlug());
    }

    /** Drop this node's cached targets (control channel). */
    public void forgetTargets(String slug) {
        targets.invalidate(slug);
    }

    /** Local invalidation now, every other node via the control channel. */
    private void targetsChanged(String slug) {
        targets.invalidate(slug);
        try {
            gateway.publishControl(new RealtimeGateway.ControlMessage("webhooks.changed", slug, null));
        } catch (Exception e) {
            log.warn("Could not broadcast webhook change for {}: {}", slug, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<WebhookDelivery> deliveries(Tenant tenant, Long id) {
        owned(tenant, id);
        return deliveries.recent(id, PageRequest.of(0, 100));
    }

    /** Send a ping synchronously and return the recorded delivery. */
    public WebhookDelivery test(Tenant tenant, Long id) {
        Webhook webhook = owned(tenant, id);
        DomainEvent ping = new DomainEvent("evt_" + Ids.newId(), DomainEvent.PING, tenant.getSlug(), Instant.now(),
                java.util.Map.of("message", "Webhook test from the chat platform"), List.of(), null, null, null);
        Target target = new Target(webhook.getId(), webhook.getUrl(), cipher.decrypt(webhook.getSecret()), Set.of(DomainEvent.PING));
        WebhookDelivery delivery = newDelivery(target, ping);
        attempt(target, ping, delivery, false);
        return delivery;
    }

    private void apply(Webhook webhook, WebhookInput input) {
        if (input.url() == null || input.url().isBlank()) {
            throw ApiException.badRequest("url is required");
        }
        urlPolicy.check(input.url());
        Set<String> events = new LinkedHashSet<>(input.events() == null ? List.of() : input.events());
        if (events.isEmpty()) {
            throw ApiException.badRequest("Select at least one event");
        }
        for (String event : events) {
            if (!DomainEvent.WEBHOOK_TYPES.contains(event)) {
                throw ApiException.badRequest("Unknown event '" + event + "'. Valid: " + DomainEvent.WEBHOOK_TYPES);
            }
        }
        webhook.setUrl(input.url().trim());
        webhook.setEvents(String.join(",", events));
        webhook.setDescription(input.description());
        if (input.active() != null) {
            webhook.setActive(input.active());
        }
    }

    private Webhook owned(Tenant tenant, Long id) {
        return webhooks.findById(id).filter(w -> w.getTenant().getId().equals(tenant.getId()))
                .orElseThrow(() -> ApiException.notFound("Webhook"));
    }

    private static WebhookView view(Webhook w, String plaintextSecret) {
        return new WebhookView(w.getId(), w.getUrl(), w.eventTypes(), w.getDescription(), w.isActive(),
                plaintextSecret != null ? plaintextSecret : ConnectorConfigService.MASK, w.getCreatedAt(), w.getUpdatedAt());
    }

    // ------------------------------------------------------------------ dispatch

    private void onEvent(DomainEvent event) {
        if (!DomainEvent.WEBHOOK_TYPES.contains(event.type())) {
            return;
        }
        boolean enabled = tenants.find(event.tenant()).map(t -> t.isUsable() && t.has(Feature.WEBHOOKS)).orElse(false);
        if (!enabled) {
            return;
        }
        for (Target target : targets.get(event.tenant(), this::loadTargets)) {
            if (target.events().contains(event.type())) {
                WebhookDelivery delivery = newDelivery(target, event);
                executor.execute(() -> attempt(target, event, delivery, true));
            }
        }
    }

    private List<Target> loadTargets(String slug) {
        return webhooks.findActiveBySlug(slug).stream()
                .map(w -> new Target(w.getId(), w.getUrl(), cipher.decrypt(w.getSecret()), w.eventTypes()))
                .toList();
    }

    private WebhookDelivery newDelivery(Target target, DomainEvent event) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setWebhookId(target.id());
        delivery.setEventId(event.id());
        delivery.setEventType(event.type());
        delivery.setStatus(WebhookDelivery.Status.PENDING);
        delivery.setCreatedAt(Instant.now());
        return deliveries.save(delivery);
    }

    private void attempt(Target target, DomainEvent event, WebhookDelivery delivery, boolean retry) {
        String body = Json.write(event.toWebhookPayload());
        long timestamp = Instant.now().getEpochSecond();
        long start = System.nanoTime();
        delivery.setAttempts(delivery.getAttempts() + 1);
        delivery.setLastAttemptAt(Instant.now());
        try {
            // Re-checked at send time: DNS may have changed since the webhook was saved.
            URI uri = urlPolicy.check(target.url());
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "chat-platform-webhooks/2")
                    .header("X-Chat-Event", event.type())
                    .header("X-Chat-Delivery", event.id())
                    .header("X-Chat-Timestamp", String.valueOf(timestamp))
                    .header("X-Chat-Signature", "sha256=" + sign(target.secret(), timestamp + "." + body))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            delivery.setResponseCode(response.statusCode());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                delivery.setStatus(WebhookDelivery.Status.SUCCESS);
                delivery.setError(null);
            } else {
                delivery.setError("HTTP " + response.statusCode());
                delivery.setStatus(WebhookDelivery.Status.FAILED);
            }
        } catch (Exception e) {
            delivery.setError(truncate(e.getClass().getSimpleName() + ": " + e.getMessage()));
            delivery.setStatus(WebhookDelivery.Status.FAILED);
        }
        delivery.setDurationMs((System.nanoTime() - start) / 1_000_000);
        boolean willRetry = retry && delivery.getStatus() == WebhookDelivery.Status.FAILED && delivery.getAttempts() < MAX_ATTEMPTS;
        if (willRetry) {
            delivery.setStatus(WebhookDelivery.Status.PENDING);
        }
        deliveries.save(delivery);
        if (willRetry) {
            long delaySeconds = (long) Math.pow(2, delivery.getAttempts()) * 5;
            executor.schedule(() -> attempt(target, event, delivery, true), delaySeconds, TimeUnit.SECONDS);
        }
    }

    static String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 997) + "...";
    }

    /** Keep 14 days of delivery history. */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    @Transactional
    public void purgeOldDeliveries() {
        int purged = deliveries.purgeBefore(Instant.now().minus(Duration.ofDays(14)));
        if (purged > 0) {
            log.info("Purged {} old webhook deliveries", purged);
        }
    }
}
