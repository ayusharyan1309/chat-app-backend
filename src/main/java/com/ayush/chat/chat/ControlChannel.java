package com.ayush.chat.chat;

import com.ayush.chat.chat.ws.SessionRegistry;
import com.ayush.chat.tenant.TenantDirectory;
import com.ayush.chat.tenant.TenantService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
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

    @PostConstruct
    void subscribe() {
        gateway.subscribeControl(this::onControl);
    }

    @EventListener
    public void onTenantChanged(TenantService.TenantChangedEvent event) {
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
            default -> log.debug("Ignoring control message {}", message.type());
        }
    }
}
