package com.ayush.chat.chat.webhook;

import com.ayush.chat.audit.AuditAction;
import com.ayush.chat.audit.AuditService;
import com.ayush.chat.chat.event.DomainEvent;
import com.ayush.chat.chat.webhook.WebhookViews.DeliveryView;
import com.ayush.chat.chat.webhook.WebhookViews.WebhookInput;
import com.ayush.chat.chat.webhook.WebhookViews.WebhookView;
import com.ayush.chat.core.crypto.SecretCipher;
import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.core.net.OutboundUrlPolicy;
import com.ayush.chat.core.util.Ids;
import com.ayush.chat.tenant.Feature;
import com.ayush.chat.tenant.TenantDirectory;
import com.ayush.chat.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Managing a tenant's webhooks from the dashboard. */
@Service
@RequiredArgsConstructor
public class WebhookAdminService {

    private static final int DELIVERY_PAGE = 100;

    private final WebhookRepository webhooks;
    private final WebhookDeliveryRepository deliveries;
    private final TenantRepository tenantRepository;
    private final TenantDirectory tenants;
    private final SecretCipher cipher;
    private final OutboundUrlPolicy urlPolicy;
    private final WebhookTargets targets;
    private final WebhookDispatcher dispatcher;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<WebhookView> list(String tenant) {
        requireTenant(tenant);
        return webhooks.findByTenantSlug(tenant).stream().map(w -> WebhookView.of(w, null)).toList();
    }

    @Transactional
    public WebhookView create(String tenant, WebhookInput input) {
        tenants.get(tenant).require(Feature.WEBHOOKS);
        Webhook webhook = new Webhook();
        webhook.setTenant(tenantRepository.findBySlug(tenant).orElseThrow(() -> DomainException.notFound("Tenant '" + tenant + "'")));
        apply(webhook, input);
        String secret = "whsec_" + Ids.randomToken(32);
        webhook.setSecret(cipher.encrypt(secret));
        webhooks.save(webhook);
        targets.changed(tenant);
        audit.record(AuditAction.WEBHOOK_CREATED, String.valueOf(webhook.getId()), tenant, webhook.getUrl());
        return WebhookView.of(webhook, secret);
    }

    @Transactional
    public WebhookView update(String tenant, Long id, WebhookInput input) {
        Webhook webhook = owned(tenant, id);
        apply(webhook, input);
        webhooks.save(webhook);
        targets.changed(tenant);
        audit.record(AuditAction.WEBHOOK_UPDATED, String.valueOf(id), tenant, webhook.getUrl());
        return WebhookView.of(webhook, null);
    }

    @Transactional
    public void delete(String tenant, Long id) {
        webhooks.delete(owned(tenant, id));
        targets.changed(tenant);
        audit.record(AuditAction.WEBHOOK_DELETED, String.valueOf(id), tenant, null);
    }

    @Transactional(readOnly = true)
    public List<DeliveryView> deliveries(String tenant, Long id) {
        owned(tenant, id);
        return deliveries.recent(id, PageRequest.of(0, DELIVERY_PAGE)).stream().map(DeliveryView::of).toList();
    }

    /** Send a signed ping now and return the recorded delivery. */
    public DeliveryView test(String tenant, Long id) {
        Webhook webhook = owned(tenant, id);
        DomainEvent ping = new DomainEvent("evt_" + Ids.newId(), DomainEvent.PING, tenant, Instant.now(),
                Map.of("message", "Webhook test from the chat platform"), List.of(), null, null, null);
        return DeliveryView.of(dispatcher.deliverNow(targets.target(webhook), ping));
    }

    private void apply(Webhook webhook, WebhookInput input) {
        if (input.url() == null || input.url().isBlank()) {
            throw DomainException.badRequest("url is required");
        }
        urlPolicy.check(input.url());
        Set<String> events = new LinkedHashSet<>(input.events() == null ? List.of() : input.events());
        if (events.isEmpty()) {
            throw DomainException.badRequest("Select at least one event");
        }
        for (String event : events) {
            if (!DomainEvent.WEBHOOK_TYPES.contains(event)) {
                throw DomainException.badRequest("Unknown event '" + event + "'. Valid: " + DomainEvent.WEBHOOK_TYPES);
            }
        }
        webhook.setUrl(input.url().trim());
        webhook.setEventTypes(events);
        webhook.setDescription(input.description());
        if (input.active() != null) {
            webhook.setActive(input.active());
        }
    }

    private Webhook owned(String tenant, Long id) {
        return webhooks.findByIdAndTenantSlug(id, tenant).orElseThrow(() -> DomainException.notFound("Webhook"));
    }

    private void requireTenant(String tenant) {
        if (!tenantRepository.existsBySlug(tenant)) {
            throw DomainException.notFound("Tenant '" + tenant + "'");
        }
    }
}
