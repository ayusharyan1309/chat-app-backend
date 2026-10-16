package com.ayush.chat.webhook.service;

import com.ayush.chat.chat.event.ControlMessage;
import com.ayush.chat.chat.port.RealtimePublisher;
import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.crypto.SecretCipher;
import com.ayush.chat.core.util.AfterCommit;
import com.ayush.chat.webhook.entity.Webhook;
import com.ayush.chat.webhook.repository.WebhookRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** Each tenant's active webhooks with decrypted secrets, cached per node for the dispatch hot path. */
@Slf4j
@Component
public class WebhookTargets {

    record Target(Long id, String url, String secret, Set<String> events) {
    }

    private final WebhookRepository webhooks;
    private final SecretCipher cipher;
    private final RealtimePublisher publisher;
    private final Cache<String, List<Target>> cache;

    public WebhookTargets(WebhookRepository webhooks, SecretCipher cipher, RealtimePublisher publisher,
                          PlatformProperties properties) {
        this.webhooks = webhooks;
        this.cipher = cipher;
        this.publisher = publisher;
        this.cache = Caffeine.newBuilder().expireAfterWrite(properties.getCaches().getWebhookTargets()).maximumSize(10_000).build();
    }

    List<Target> active(String tenant) {
        return cache.get(tenant, slug -> webhooks.findActiveByTenantSlug(slug).stream().map(this::target).toList());
    }

    Target target(Webhook w) {
        return new Target(w.getId(), w.getUrl(), cipher.decrypt(w.getSecret()), w.getEventTypes());
    }

    /** Drop this node's cached targets (control channel). */
    public void forget(String tenant) {
        cache.invalidate(tenant);
    }

    /** After the change commits: invalidate here, then on every other node. */
    void changed(String tenant) {
        AfterCommit.run(() -> {
            forget(tenant);
            try {
                publisher.publishControl(ControlMessage.webhooksChanged(tenant));
            } catch (Exception e) {
                log.warn("Could not broadcast webhook change for {}: {}", tenant, e.getMessage());
            }
        });
    }
}
