package com.ayush.chat.chat.service;

import com.ayush.chat.core.connector.runtime.TenantConnectors;
import com.ayush.chat.tenant.model.TenantSnapshot;
import com.ayush.chat.tenant.service.TenantDirectory;
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
