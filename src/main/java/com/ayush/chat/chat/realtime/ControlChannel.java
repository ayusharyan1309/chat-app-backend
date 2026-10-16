package com.ayush.chat.chat.realtime;

import com.ayush.chat.core.cluster.BusTopic;
import com.ayush.chat.core.cluster.ClusterBus;
import com.ayush.chat.chat.application.BlockService;
import com.ayush.chat.chat.application.ConversationAccess;
import com.ayush.chat.chat.application.UserStatusService;
import com.ayush.chat.chat.event.ControlMessage;
import com.ayush.chat.chat.webhook.WebhookTargets;
import com.ayush.chat.chat.ws.SessionRegistry;
import com.ayush.chat.tenant.PlanChangedEvent;
import com.ayush.chat.tenant.TenantChangedEvent;
import com.ayush.chat.tenant.TenantDirectory;
import com.ayush.chat.tenant.TenantSnapshot;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Applies administrative changes on every node: drops cached tenants, users, conversations, blocks
 * and webhook targets, and closes the sockets of suspended tenants and deactivated users.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ControlChannel {

    private final ClusterBus bus;
    private final RealtimeGateway gateway;
    private final TenantDirectory tenants;
    private final SessionRegistry sessions;
    private final UserStatusService userStatus;
    private final ConversationAccess conversations;
    private final BlockService blocks;
    private final WebhookTargets webhookTargets;

    @PostConstruct
    void subscribe() {
        bus.subscribe(BusTopic.CONTROL, null, ControlMessage.class, this::apply);
    }

    /** After commit, so no node (this one included) reloads a tenant mid-transaction. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onTenantChanged(TenantChangedEvent event) {
        broadcast(ControlMessage.tenantChanged(event.slug()));
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onPlanChanged(PlanChangedEvent event) {
        broadcast(ControlMessage.plansChanged());
    }

    /** Applied here immediately, then on every other node (this one sees its own message again; harmless). */
    private void broadcast(ControlMessage message) {
        apply(message);
        try {
            gateway.publishControl(message);
        } catch (Exception e) {
            log.warn("Could not broadcast {} for {}: {}", message.type(), message.tenant(), e.getMessage());
        }
    }

    void apply(ControlMessage message) {
        switch (message.type()) {
            case TENANT_CHANGED -> {
                tenants.invalidate(message.tenant());
                boolean usable = tenants.find(message.tenant()).map(TenantSnapshot::isUsable).orElse(false);
                if (!usable) {
                    int closed = sessions.closeTenant(message.tenant(), "Tenant unavailable");
                    if (closed > 0) {
                        log.info("Closed {} sessions of tenant {}", closed, message.tenant());
                    }
                }
            }
            case USER_DEACTIVATED -> {
                userStatus.forget(message.tenant(), message.userId());
                sessions.closeUser(message.tenant(), message.userId(), "User deactivated");
            }
            case CONVERSATION_CHANGED -> conversations.forget(message.tenant(), message.ref());
            case BLOCK_CHANGED -> blocks.forget(message.tenant(), message.ref());
            case WEBHOOKS_CHANGED -> webhookTargets.forget(message.tenant());
            case PLANS_CHANGED -> tenants.invalidateAll();
            case UNKNOWN -> log.debug("Ignoring unknown control message for {}", message.tenant());
        }
    }
}
