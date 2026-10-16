package com.ayush.chat.tenant;

import com.ayush.chat.core.crypto.SecretCipher;
import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.core.util.Json;
import com.ayush.chat.tenant.TenantRepositories.PlanRepository;
import com.ayush.chat.tenant.TenantRepositories.TenantRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves tenants to {@link TenantSnapshot}s with a short local cache. Changes made through
 * {@link TenantService} invalidate locally and are broadcast to other nodes; the TTL bounds
 * staleness if a broadcast is missed.
 */
@Service
@RequiredArgsConstructor
public class TenantDirectory {

    private final TenantRepository tenants;
    private final PlanRepository plans;
    private final SecretCipher cipher;

    private final Cache<String, Optional<TenantSnapshot>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(15))
            .maximumSize(100_000)
            .build();

    /** Snapshot for a tenant, or 404. Does not check status — use {@link #requireUsable}. */
    public TenantSnapshot get(String slug) {
        if (slug == null || slug.isBlank()) {
            throw DomainException.badRequest("Tenant is required");
        }
        return find(slug).orElseThrow(() -> DomainException.notFound("Tenant '" + slug + "'"));
    }

    /** Only hits are cached; a miss is re-checked next time so a just-created tenant is visible at once. */
    public Optional<TenantSnapshot> find(String slug) {
        Optional<TenantSnapshot> cached = cache.getIfPresent(slug);
        if (cached != null) {
            return cached;
        }
        Optional<TenantSnapshot> loaded = load(slug);
        loaded.ifPresent(snapshot -> cache.put(slug, loaded));
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

    Optional<TenantSnapshot> load(String slug) {
        return tenants.findBySlugWithFeatures(slug)
                .filter(t -> t.getStatus() != Tenant.Status.DELETED)
                .map(this::toSnapshot);
    }

    TenantSnapshot toSnapshot(Tenant tenant) {
        Plan plan = plans.findByCode(tenant.getPlanCode()).orElse(null);
        TenantLimits planLimits = plan != null ? plan.limits() : TenantLimits.NONE;
        TenantLimits overrides = Optional.ofNullable(Json.read(tenant.getLimitsJson(), TenantLimits.class)).orElse(TenantLimits.NONE);
        return new TenantSnapshot(
                tenant.getId(),
                tenant.getSlug(),
                tenant.getName(),
                tenant.getStatus(),
                tenant.getPlanCode(),
                plan != null ? plan.getName() : tenant.getPlanCode(),
                planLimits.overriddenBy(overrides),
                settings(tenant),
                branding(tenant),
                auth(tenant, true),
                effectiveFeatures(plan, tenant),
                tenant.getTrialEndsAt());
    }

    TenantConfig.Settings settings(Tenant tenant) {
        return Optional.ofNullable(Json.read(tenant.getSettingsJson(), TenantConfig.Settings.class))
                .orElse(TenantConfig.Settings.DEFAULT).withDefaults();
    }

    TenantConfig.Branding branding(Tenant tenant) {
        return Optional.ofNullable(Json.read(tenant.getBrandingJson(), TenantConfig.Branding.class))
                .orElse(TenantConfig.Branding.DEFAULT);
    }

    /** Auth config; secrets decrypted when {@code decrypt} is true. */
    TenantConfig.Auth auth(Tenant tenant, boolean decrypt) {
        TenantConfig.Auth auth = Optional.ofNullable(Json.read(tenant.getAuthJson(), TenantConfig.Auth.class))
                .orElse(TenantConfig.Auth.DEFAULT).withDefaults();
        TenantConfig.External ext = auth.external();
        if (decrypt && ext.jwtSecret() != null) {
            ext = new TenantConfig.External(ext.type(), ext.firebaseProjectId(), ext.jwtIssuer(), ext.jwtAudience(),
                    ext.jwksUrl(), cipher.decrypt(ext.jwtSecret()), ext.userIdClaim(), ext.nameClaim(), ext.emailClaim());
        }
        return new TenantConfig.Auth(auth.devLoginEnabled(), auth.tokenTtlMinutes(), ext);
    }

    static Set<Feature> effectiveFeatures(Plan plan, Tenant tenant) {
        Set<Feature> enabled = EnumSet.noneOf(Feature.class);
        enabled.addAll(Feature.core());
        if (plan != null) {
            plan.featureIds().forEach(id -> Feature.fromId(id).filter(Feature::isToggleable).ifPresent(enabled::add));
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
        return enabled.isEmpty() ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(enabled);
    }

    static String sourceOf(Feature feature, Plan plan, Tenant tenant) {
        if (feature.isCore()) return "core";
        if (feature.getStatus() == Feature.Status.PLANNED) return "planned";
        boolean overridden = tenant.getFeatureOverrides().stream().anyMatch(o -> o.getFeatureId().equals(feature.getId()));
        return overridden ? "override" : "plan";
    }
}
