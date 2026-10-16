package com.ayush.chat.chat.application;

import com.ayush.chat.auth.ActiveUserCheck;
import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.connector.runtime.TenantConnectors;
import com.ayush.chat.core.error.DomainException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

/**
 * Whether an end user may act, checked on every authenticated request and socket CONNECT. Cached
 * per node; deactivation is pushed to all nodes over the control channel.
 */
@Service
public class UserStatusService implements ActiveUserCheck {

    private final TenantConnectors connectors;
    private final Cache<String, Boolean> active;

    public UserStatusService(TenantConnectors connectors, PlatformProperties properties) {
        this.connectors = connectors;
        this.active = Caffeine.newBuilder().expireAfterWrite(properties.getCaches().getUserStatus()).maximumSize(500_000).build();
    }

    @Override
    public void requireActiveUser(String tenant, String userId) {
        Boolean isActive = active.get(key(tenant, userId), k -> connectors.store(tenant).findUser(tenant, userId)
                .map(u -> !u.isDeactivated()).orElse(false));
        if (!Boolean.TRUE.equals(isActive)) {
            throw DomainException.forbidden("User '" + userId + "' does not exist or is deactivated");
        }
    }

    void markDeactivated(String tenant, String userId) {
        active.put(key(tenant, userId), false);
    }

    public void forget(String tenant, String userId) {
        active.invalidate(key(tenant, userId));
    }

    private static String key(String tenant, String userId) {
        return tenant + "::" + userId;
    }
}
