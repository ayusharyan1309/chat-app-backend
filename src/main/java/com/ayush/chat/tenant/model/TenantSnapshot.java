package com.ayush.chat.tenant.model;

import com.ayush.chat.core.exception.DomainException;
import com.ayush.chat.tenant.entity.Tenant;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable, cache-friendly view of a tenant with everything the hot path needs: effective
 * limits (plan + overrides), effective features, settings and auth configuration.
 */
public record TenantSnapshot(
                             Long id,
                             String slug,
                             String name,
                             Tenant.Status status,
                             String planCode,
                             String planName,
                             TenantLimits limits,
                             TenantConfig.Settings settings,
                             TenantConfig.Branding branding,
                             TenantConfig.Auth auth,
                             Set<Feature> features,
                             Instant trialEndsAt) {

    public boolean isUsable() {
        return status.isUsable();
    }

    public boolean has(Feature feature) {
        return feature.isCore() || features.contains(feature);
    }

    public void require(Feature feature) {
        if (!has(feature)) {
            throw DomainException.featureDisabled(feature.getId());
        }
    }

    public void requireUsable() {
        if (!isUsable()) {
            throw DomainException.forbidden("Tenant '" + slug + "' is " + status.name().toLowerCase(Locale.ROOT));
        }
    }
}
