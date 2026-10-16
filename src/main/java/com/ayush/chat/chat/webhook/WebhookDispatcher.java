package com.ayush.chat.chat.webhook;

import com.ayush.chat.chat.event.DomainEvent;
import com.ayush.chat.core.cluster.BusTopic;
import com.ayush.chat.core.cluster.ClusterBus;
import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.crypto.WebhookSignature;
import com.ayush.chat.core.net.OutboundUrlPolicy;
import com.ayush.chat.core.util.Json;
import com.ayush.chat.tenant.Feature;
import com.ayush.chat.tenant.TenantDirectory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * At-least-once delivery of domain events to tenant webhooks, signed with
 * {@link WebhookSignature} and retried with exponential backoff. Consumes the bus as a work queue,
 * so each event is dispatched by exactly one node. Requests run on virtual threads, so a slow
 * receiver never starves the others.
 */
@Slf4j
@Service
public class WebhookDispatcher {

    static final String USER_AGENT = "chat-platform-webhooks/2";
    private static final int MAX_ERROR_LENGTH = 1000;

    private final WebhookDeliveryRepository deliveries;
    private final WebhookTargets targets;
    private final TenantDirectory tenants;
    private final OutboundUrlPolicy urlPolicy;
    private final ClusterBus bus;
    private final PlatformProperties.Webhooks settings;
    private final HttpClient http;
    private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "webhook-retry");
        t.setDaemon(true);
        return t;
    });

    public WebhookDispatcher(WebhookDeliveryRepository deliveries, WebhookTargets targets, TenantDirectory tenants,
                             OutboundUrlPolicy urlPolicy, ClusterBus bus, PlatformProperties properties) {
        this.deliveries = deliveries;
        this.targets = targets;
        this.tenants = tenants;
        this.urlPolicy = urlPolicy;
        this.bus = bus;
        this.settings = properties.getWebhooks();
        this.http = HttpClient.newBuilder().connectTimeout(settings.getRequestTimeout()).build();
    }

    @PostConstruct
    void subscribe() {
        bus.subscribe(BusTopic.DOMAIN_EVENTS, "webhooks", DomainEvent.class, this::onEvent);
    }

    @PreDestroy
    void stop() {
        retries.shutdownNow();
        senders.shutdownNow();
    }

    void onEvent(DomainEvent event) {
        if (!DomainEvent.WEBHOOK_TYPES.contains(event.type())) {
            return;
        }
        boolean enabled = tenants.find(event.tenant()).map(t -> t.isUsable() && t.has(Feature.WEBHOOKS)).orElse(false);
        if (!enabled) {
            return;
        }
        for (WebhookTargets.Target target : targets.active(event.tenant())) {
            if (target.events().contains(event.type())) {
                WebhookDelivery delivery = newDelivery(target, event);
                senders.execute(() -> attempt(target, event, delivery, true));
            }
        }
    }

    /** Deliver once, synchronously, without retries (the dashboard's "send test event"). */
    WebhookDelivery deliverNow(WebhookTargets.Target target, DomainEvent event) {
        WebhookDelivery delivery = newDelivery(target, event);
        attempt(target, event, delivery, false);
        return delivery;
    }

    private WebhookDelivery newDelivery(WebhookTargets.Target target, DomainEvent event) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setWebhookId(target.id());
        delivery.setEventId(event.id());
        delivery.setEventType(event.type());
        delivery.setStatus(WebhookDelivery.Status.PENDING);
        delivery.setCreatedAt(Instant.now());
        return deliveries.save(delivery);
    }

    private void attempt(WebhookTargets.Target target, DomainEvent event, WebhookDelivery delivery, boolean retry) {
        String body = Json.write(event.toWebhookPayload());
        long timestamp = Instant.now().getEpochSecond();
        long start = System.nanoTime();
        delivery.setAttempts(delivery.getAttempts() + 1);
        delivery.setLastAttemptAt(Instant.now());
        try {
            // Re-checked at send time: DNS may have changed since the webhook was saved.
            URI uri = urlPolicy.check(target.url());
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(uri)
                    .timeout(settings.getRequestTimeout())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .header(WebhookSignature.EVENT_HEADER, event.type())
                    .header(WebhookSignature.DELIVERY_HEADER, event.id())
                    .header(WebhookSignature.TIMESTAMP_HEADER, String.valueOf(timestamp))
                    .header(WebhookSignature.SIGNATURE_HEADER, WebhookSignature.sign(target.secret(), timestamp, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding());
            delivery.setResponseCode(response.statusCode());
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            delivery.setStatus(ok ? WebhookDelivery.Status.SUCCESS : WebhookDelivery.Status.FAILED);
            delivery.setError(ok ? null : "HTTP " + response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            delivery.setStatus(WebhookDelivery.Status.FAILED);
            delivery.setError("Interrupted");
        } catch (Exception e) {
            delivery.setError(truncate(e.getClass().getSimpleName() + ": " + e.getMessage()));
            delivery.setStatus(WebhookDelivery.Status.FAILED);
        }
        delivery.setDurationMs((System.nanoTime() - start) / 1_000_000);
        boolean willRetry = retry && delivery.getStatus() == WebhookDelivery.Status.FAILED
                && delivery.getAttempts() < settings.getMaxAttempts();
        if (willRetry) {
            delivery.setStatus(WebhookDelivery.Status.PENDING);
        }
        deliveries.save(delivery);
        if (willRetry && !retries.isShutdown()) {
            long delay = settings.getInitialBackoff().toMillis() << (delivery.getAttempts() - 1);
            retries.schedule(() -> senders.execute(() -> attempt(target, event, delivery, true)), delay, TimeUnit.MILLISECONDS);
        }
    }

    private static String truncate(String s) {
        return s == null || s.length() <= MAX_ERROR_LENGTH ? s : s.substring(0, MAX_ERROR_LENGTH - 3) + "...";
    }

    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT5M")
    @Transactional
    public void purgeOldDeliveries() {
        int purged = deliveries.purgeBefore(Instant.now().minus(settings.getDeliveryRetention()));
        if (purged > 0) {
            log.info("Purged {} old webhook deliveries", purged);
        }
    }
}
