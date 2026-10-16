package com.ayush.chat.admin.tenant;

import com.ayush.chat.auth.ApiKey;
import com.ayush.chat.chat.Views.UserView;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.tenant.Feature;
import com.ayush.chat.tenant.FeatureSource;
import com.ayush.chat.tenant.Tenant;
import com.ayush.chat.tenant.TenantConfig;
import com.ayush.chat.tenant.TenantLimits;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Dashboard representations of tenants and their credentials. */
public final class TenantViews {

    private TenantViews() {
    }

    public record CreateTenant(String name, String slug, String contactEmail, String domain, String planCode, Integer trialDays) {
    }

    public record TenantSummary(String slug, String name, String contactEmail, String domain, Tenant.Status status,
                                String planCode, String planName, String storeProvider, long messagesToday,
                                int connections, Instant createdAt, Instant trialEndsAt) {
    }

    public record FeatureState(String id, String name, String category, Feature.Status status, boolean enabled,
                               FeatureSource source) {
    }

    /** {@code -1} counts mean the tenant's store could not be reached. */
    public record Stats(long users, long conversations, long messages, long messagesToday, int connections, long storageBytes) {
    }

    public record TenantDetail(String slug, String name, String contactEmail, String domain, Tenant.Status status,
                               String planCode, String planName, String storeProvider, long messagesToday, int connections,
                               Instant createdAt, Instant trialEndsAt, Instant updatedAt,
                               TenantConfig.Branding branding, TenantConfig.Settings settings, TenantLimits limits,
                               TenantLimits limitOverrides, List<FeatureState> features, Stats stats) {
    }

    /** {@code key} is the plaintext key, present only in the response that creates it. */
    public record ApiKeyView(Long id, String name, String prefix, Instant createdAt, String createdBy, Instant lastUsedAt,
                             Instant revokedAt, String key) {
        static ApiKeyView of(ApiKey k, String plaintext) {
            return new ApiKeyView(k.getId(), k.getName(), k.getKeyPrefix(), k.getCreatedAt(), k.getCreatedBy(),
                    k.getLastUsedAt(), k.getRevokedAt(), plaintext);
        }
    }

    public record CreatedTenant(TenantDetail tenant, ApiKeyView apiKey) {
    }

    public record FeatureOverrides(Map<String, Boolean> overrides) {
    }

    public record ConnectorRequest(String provider, Map<String, Object> settings) {
    }

    /** A tenant's dedicated connector, or {@code inherited} when it uses the platform default. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConnectorOverrideView(boolean inherited, String provider, Map<String, Object> settings, HealthStatus health) {
        static ConnectorOverrideView inheritedView() {
            return new ConnectorOverrideView(true, null, null, null);
        }
    }

    public record NamedRequest(String name) {
    }

    public record TokenView(String token, Instant expiresAt, UserView user) {
    }
}
