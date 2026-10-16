package com.ayush.chat.core.connector.runtime;

import com.ayush.chat.core.connector.config.ConnectorConfigChangedEvent;
import com.ayush.chat.core.connector.config.ConnectorConfigService;
import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.core.connector.ConnectorRegistry;
import com.ayush.chat.push.PushSender;
import com.ayush.chat.store.MessageStore;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Entry point for tenant-scoped connectors (message store and push). Everything that reads or
 * writes chat data goes through {@link #store(String)}.
 */
@Service
public class TenantConnectors implements DisposableBean {

    private final TenantConnectorRouter<MessageStore> stores;
    private final TenantConnectorRouter<PushSender> push;

    public TenantConnectors(ConnectorRegistry registry, ConnectorConfigService configService,
                            PlatformConnectors platform, PlatformProperties properties) {
        PlatformProperties.Connectors runtime = properties.getConnectorRuntime();
        this.stores = new TenantConnectorRouter<>(ConnectorKind.MESSAGE_STORE, registry, configService,
                platform::messageStore, runtime.getRetireGrace(), runtime.getTenantFailureBackoff());
        this.push = new TenantConnectorRouter<>(ConnectorKind.PUSH, registry, configService, platform::push,
                runtime.getRetireGrace(), runtime.getTenantFailureBackoff());
    }

    public MessageStore store(String tenantSlug) {
        return stores.forTenant(tenantSlug);
    }

    public PushSender push(String tenantSlug) {
        return push.forTenant(tenantSlug);
    }

    public TenantConnectorRouter<?> router(ConnectorKind kind) {
        return switch (kind) {
            case MESSAGE_STORE -> stores;
            case PUSH -> push;
            default -> throw DomainException.badRequest(kind.getDisplayName() + " is platform-wide and cannot be overridden per tenant");
        };
    }

    @EventListener
    public void onConfigChanged(ConnectorConfigChangedEvent event) {
        if (!event.scope().isPlatform() && event.kind().isTenantOverridable()) {
            router(event.kind()).onConfigChanged(event.scope().tenantSlug());
        }
    }

    @Override
    public void destroy() {
        stores.close();
        push.close();
    }
}
