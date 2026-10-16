package com.ayush.chat.webhook.service;

import com.ayush.chat.audit.model.AuditAction;
import com.ayush.chat.audit.service.AuditService;
import com.ayush.chat.chat.event.DomainEvent;
import com.ayush.chat.core.crypto.SecretCipher;
import com.ayush.chat.core.exception.DomainException;
import com.ayush.chat.core.net.OutboundUrlPolicy;
import com.ayush.chat.core.util.Ids;
import com.ayush.chat.tenant.model.Feature;
import com.ayush.chat.tenant.repository.TenantRepository;
import com.ayush.chat.tenant.service.TenantDirectory;
import com.ayush.chat.webhook.dto.WebhookDeliveryView;
import com.ayush.chat.webhook.dto.WebhookRequest;
import com.ayush.chat.webhook.dto.WebhookView;
import com.ayush.chat.webhook.entity.Webhook;
import com.ayush.chat.webhook.repository.WebhookDeliveryRepository;
import com.ayush.chat.webhook.repository.WebhookRepository;
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
    public WebhookView create(String tenant, WebhookRequest input) {
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
    public WebhookView update(String tenant, Long id, WebhookRequest input) {
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
    public List<WebhookDeliveryView> deliveries(String tenant, Long id) {
        owned(tenant, id);
        return deliveries.recent(id, PageRequest.of(0, DELIVERY_PAGE)).stream().map(WebhookDeliveryView::of).toList();
    }

    /** Send a signed ping now and return the recorded delivery. */
    public WebhookDeliveryView test(String tenant, Long id) {
        Webhook webhook = owned(tenant, id);
        DomainEvent ping = new DomainEvent("evt_" + Ids.newId(), DomainEvent.PING, tenant, Instant.now(),
                Map.of("message", "Webhook test from the chat platform"), List.of(), null, null, null);
        return WebhookDeliveryView.of(dispatcher.deliverNow(targets.target(webhook), ping));
    }

    private void apply(Webhook webhook, WebhookRequest input) {
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
