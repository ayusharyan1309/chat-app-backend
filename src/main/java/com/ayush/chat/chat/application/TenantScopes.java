package com.ayush.chat.chat.application;

import com.ayush.chat.core.connector.runtime.TenantConnectors;
import com.ayush.chat.tenant.TenantDirectory;
import com.ayush.chat.tenant.TenantSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TenantScopes {

    private final TenantDirectory tenants;
    private final TenantConnectors connectors;

    /** A usable tenant (404 when unknown, 403 when suspended) and its message store. */
    public TenantScope of(String slug) {
        TenantSnapshot tenant = tenants.requireUsable(slug);
        return new TenantScope(tenant, connectors.store(slug));
    }
}
