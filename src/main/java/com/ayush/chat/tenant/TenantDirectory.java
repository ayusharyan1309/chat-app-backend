package com.ayush.chat.tenant;

import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.crypto.SecretCipher;
import com.ayush.chat.core.error.DomainException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves tenants to immutable {@link TenantSnapshot}s with a short node-local cache. Changes
 * invalidate it cluster-wide over the control channel; the TTL bounds staleness if a broadcast is
 * missed.
 */
@Service
public class TenantDirectory {

    private final TenantRepository tenants;
    private final PlanRepository plans;
    private final SecretCipher cipher;
    private final Cache<String, TenantSnapshot> cache;

    public TenantDirectory(TenantRepository tenants, PlanRepository plans, SecretCipher cipher, PlatformProperties properties) {
        this.tenants = tenants;
        this.plans = plans;
        this.cipher = cipher;
        this.cache = Caffeine.newBuilder().expireAfterWrite(properties.getCaches().getTenantSnapshot()).maximumSize(100_000).build();
    }

    /** Snapshot for a tenant, or 404. Does not check status — use {@link #requireUsable}. */
    public TenantSnapshot get(String slug) {
        if (slug == null || slug.isBlank()) {
            throw DomainException.badRequest("Tenant is required");
        }
        return find(slug).orElseThrow(() -> DomainException.notFound("Tenant '" + slug + "'"));
    }

    /** Only hits are cached; a miss is re-checked next time so a just-created tenant is visible at once. */
    public Optional<TenantSnapshot> find(String slug) {
        TenantSnapshot cached = cache.getIfPresent(slug);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<TenantSnapshot> loaded = tenants.findBySlugWithFeatures(slug)
                .filter(t -> t.getStatus() != Tenant.Status.DELETED)
                .map(t -> snapshot(t, plans.findByCode(t.getPlanCode()).orElse(null)));
        loaded.ifPresent(snapshot -> cache.put(slug, snapshot));
        return loaded;
    }

    public TenantSnapshot requireUsable(String slug) {
        TenantSnapshot snapshot = get(slug);
        snapshot.requireUsable();
        return snapshot;
    }

    public void invalidate(String slug) {
        cache.invalidate(slug);
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }

    /** Snapshot of an entity already loaded (with its plan), bypassing the cache. */
    public TenantSnapshot snapshot(Tenant tenant, Plan plan) {
        return new TenantSnapshot(
                tenant.getId(),
                tenant.getSlug(),
                tenant.getName(),
                tenant.getStatus(),
                tenant.getPlanCode(),
                plan != null ? plan.getName() : tenant.getPlanCode(),
                (plan != null ? plan.limitsOrNone() : TenantLimits.NONE).overriddenBy(tenant.getLimitOverrides()),
                tenant.effectiveSettings(),
                tenant.brandingOrDefault(),
                decryptedAuth(tenant),
                Collections.unmodifiableSet(effectiveFeatures(plan, tenant)),
                tenant.getTrialEndsAt());
    }

    private TenantConfig.Auth decryptedAuth(Tenant tenant) {
        TenantConfig.Auth auth = tenant.storedAuth();
        String secret = auth.external().jwtSecret();
        return secret == null ? auth : auth.withExternal(auth.external().withJwtSecret(cipher.decrypt(secret)));
    }

    /** Core features, plus the plan's toggleable features, adjusted by the tenant's overrides. */
    static Set<Feature> effectiveFeatures(Plan plan, Tenant tenant) {
        Set<Feature> enabled = EnumSet.noneOf(Feature.class);
        enabled.addAll(Feature.core());
        if (plan != null) {
            plan.getFeatureIds().forEach(id -> Feature.fromId(id).filter(Feature::isToggleable).ifPresent(enabled::add));
        }
        for (TenantFeatureOverride override : tenant.getFeatureOverrides()) {
            Feature.fromId(override.getFeatureId()).filter(Feature::isToggleable).ifPresent(f -> {
                if (override.isEnabled()) {
                    enabled.add(f);
                } else {
                    enabled.remove(f);
                }
            });
        }
        return enabled;
    }

    static FeatureSource sourceOf(Feature feature, Tenant tenant) {
        if (feature.isCore()) {
            return FeatureSource.CORE;
        }
        if (feature.getStatus() == Feature.Status.PLANNED) {
            return FeatureSource.PLANNED;
        }
        boolean overridden = tenant.getFeatureOverrides().stream().anyMatch(o -> o.getFeatureId().equals(feature.getId()));
        return overridden ? FeatureSource.OVERRIDE : FeatureSource.PLAN;
    }
}
