package com.ayush.chat.tenant.model;

import jakarta.persistence.Embeddable;

/**
 * Quantitative limits. A null value means "unlimited" on a plan, or "inherit the plan" when used
 * as a tenant override.
 */
@Embeddable
public record TenantLimits(Long maxUsers, Long maxMessagesPerDay, Long maxConnections, Long maxStorageMb,
                           Integer maxGroupSize, Integer messagesPerSecond) {

    public static final TenantLimits NONE = new TenantLimits(null, null, null, null, null, null);

    /** Tenant overrides take precedence over the plan. */
    public TenantLimits overriddenBy(TenantLimits overrides) {
        if (overrides == null) {
            return this;
        }
        return new TenantLimits(
                overrides.maxUsers != null ? overrides.maxUsers : maxUsers,
                overrides.maxMessagesPerDay != null ? overrides.maxMessagesPerDay : maxMessagesPerDay,
                overrides.maxConnections != null ? overrides.maxConnections : maxConnections,
                overrides.maxStorageMb != null ? overrides.maxStorageMb : maxStorageMb,
                overrides.maxGroupSize != null ? overrides.maxGroupSize : maxGroupSize,
                overrides.messagesPerSecond != null ? overrides.messagesPerSecond : messagesPerSecond);
    }
}
