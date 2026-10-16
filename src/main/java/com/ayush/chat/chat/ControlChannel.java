package com.ayush.chat.chat;

import com.ayush.chat.chat.webhook.WebhookService;
import com.ayush.chat.chat.ws.SessionRegistry;
import com.ayush.chat.tenant.TenantDirectory;
import com.ayush.chat.tenant.TenantService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Component;

/**
 * Propagates administrative changes to every node: tenant updates invalidate cached snapshots,
 * suspended tenants and deactivated users have their sockets closed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ControlChannel {

    private final RealtimeGateway gateway;
    private final TenantDirectory tenants;
    private final SessionRegistry sessions;
    private final ChatService chat;
    private final WebhookService webhooks;

    @PostConstruct
    void subscribe() {
        gateway.subscribeControl(this::onControl);
    }

    /** After commit, so other nodes (and this one) never reload a tenant mid-transaction. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onTenantChanged(TenantService.TenantChangedEvent event) {
        tenants.invalidate(event.slug());
        try {
            gateway.publishControl(new RealtimeGateway.ControlMessage("tenant.changed", event.slug(), null));
        } catch (Exception e) {
            log.warn("Could not broadcast tenant change for {}: {}", event.slug(), e.getMessage());
            onControl(new RealtimeGateway.ControlMessage("tenant.changed", event.slug(), null));
        }
    }

    private void onControl(RealtimeGateway.ControlMessage message) {
        switch (message.type()) {
            case "tenant.changed" -> {
                tenants.invalidate(message.tenant());
                boolean usable = tenants.find(message.tenant()).map(t -> t.isUsable()).orElse(false);
                if (!usable) {
                    int closed = sessions.closeTenant(message.tenant(), "Tenant unavailable");
                    if (closed > 0) {
                        log.info("Closed {} sessions of tenant {}", closed, message.tenant());
                    }
                }
            }
            case "user.deactivated" -> {
                chat.forgetUser(message.tenant(), message.userId());
                sessions.closeUser(message.tenant(), message.userId(), "User deactivated");
            }
            case "conversation.changed" -> chat.forgetConversation(message.tenant(), message.ref());
            case "block.changed" -> chat.forgetBlock(message.tenant(), message.ref());
            case "webhooks.changed" -> webhooks.forgetTargets(message.tenant());
            default -> log.debug("Ignoring control message {}", message.type());
        }
    }
}
