package com.ayush.chat.tenant;

import com.ayush.chat.core.connector.config.ConfigScope;
import com.ayush.chat.core.connector.config.ConnectorConfigService;
import com.ayush.chat.core.crypto.SecretCipher;
import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.core.net.OutboundUrlPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tenant lifecycle and configuration. Every mutation publishes a {@link TenantChangedEvent}, which
 * invalidates cached snapshots cluster-wide once the transaction commits.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,47}$");
    private static final int MIN_TOKEN_TTL_MINUTES = 5;
    private static final int MAX_TOKEN_TTL_MINUTES = 60 * 24 * 30;
    private static final int MIN_HS256_SECRET_LENGTH = 32;
    private static final int MAX_MESSAGE_LENGTH = 65_536;
    private static final int MAX_FILE_SIZE_MB = 2048;

    private final TenantRepository tenants;
    private final PlanRepository plans;
    private final TenantDirectory directory;
    private final SecretCipher cipher;
    private final ConnectorConfigService connectorConfigs;
    private final ApplicationEventPublisher events;
    private final OutboundUrlPolicy urlPolicy;

    public record TenantInput(String name, String slug, String contactEmail, String domain, String planCode, Integer trialDays) {
    }

    public record TenantUpdate(String name, String contactEmail, String domain, String planCode,
                               TenantConfig.Branding branding, TenantConfig.Settings settings, TenantLimits limitOverrides) {
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<Tenant> list(String query, Tenant.Status status) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return tenants.findAllVisible().stream()
                .filter(t -> status == null || t.getStatus() == status)
                .filter(t -> q.isEmpty() || t.getSlug().contains(q) || t.getName().toLowerCase(Locale.ROOT).contains(q)
                        || (t.getContactEmail() != null && t.getContactEmail().toLowerCase(Locale.ROOT).contains(q)))
                .toList();
    }

    /** The tenant with its feature overrides loaded, or 404 (deleted tenants do not exist). */
    @Transactional(readOnly = true)
    public Tenant get(String slug) {
        return tenants.findBySlugWithFeatures(slug)
                .filter(t -> t.getStatus() != Tenant.Status.DELETED)
                .orElseThrow(() -> DomainException.notFound("Tenant '" + slug + "'"));
    }

    // ------------------------------------------------------------------ lifecycle

    @Transactional
    public Tenant create(TenantInput input) {
        if (input.name() == null || input.name().isBlank()) {
            throw DomainException.badRequest("name is required");
        }
        String slug = input.slug() == null || input.slug().isBlank() ? uniqueSlug(input.name()) : input.slug().trim();
        if (!SLUG.matcher(slug).matches()) {
            throw DomainException.badRequest("slug must be 2-48 chars: lowercase letters, digits and dashes");
        }
        if (tenants.existsBySlug(slug)) {
            throw DomainException.conflict("Tenant slug '" + slug + "' is already taken");
        }
        Plan plan = input.planCode() == null || input.planCode().isBlank()
                ? plans.findFirstByDefaultPlanTrue().orElseThrow(() -> DomainException.badRequest("No default plan configured"))
                : activePlan(input.planCode());
        Tenant tenant = new Tenant();
        tenant.setSlug(slug);
        tenant.setName(input.name().trim());
        tenant.setContactEmail(blankToNull(input.contactEmail()));
        tenant.setDomain(blankToNull(input.domain()));
        tenant.setPlanCode(plan.getCode());
        if (input.trialDays() != null && input.trialDays() > 0) {
            tenant.setStatus(Tenant.Status.TRIAL);
            tenant.setTrialEndsAt(Instant.now().plus(Duration.ofDays(input.trialDays())));
        } else {
            tenant.setStatus(Tenant.Status.ACTIVE);
        }
        tenant.setBranding(TenantConfig.Branding.named(input.name().trim()));
        tenant.setSettings(TenantConfig.Settings.DEFAULT);
        tenant.setAuth(TenantConfig.Auth.DEFAULT);
        tenants.save(tenant);
        changed(slug);
        log.info("Created tenant {} on plan {}", slug, plan.getCode());
        return tenant;
    }

    @Transactional
    public Tenant update(String slug, TenantUpdate update) {
        Tenant tenant = get(slug);
        if (update.name() != null && !update.name().isBlank()) {
            tenant.setName(update.name().trim());
        }
        if (update.contactEmail() != null) {
            tenant.setContactEmail(blankToNull(update.contactEmail()));
        }
        if (update.domain() != null) {
            tenant.setDomain(blankToNull(update.domain()));
        }
        if (update.planCode() != null && !update.planCode().equals(tenant.getPlanCode())) {
            tenant.setPlanCode(activePlan(update.planCode()).getCode());
        }
        if (update.branding() != null) {
            tenant.setBranding(update.branding());
        }
        if (update.settings() != null) {
            validateSettings(update.settings());
            tenant.setSettings(update.settings());
        }
        if (update.limitOverrides() != null) {
            tenant.setLimitOverrides(update.limitOverrides());
        }
        changed(slug);
        return tenant;
    }

    @Transactional
    public Tenant setStatus(String slug, Tenant.Status status) {
        if (status == Tenant.Status.DELETED) {
            throw DomainException.badRequest("Use delete to remove a tenant");
        }
        Tenant tenant = get(slug);
        tenant.setStatus(status);
        tenant.setSuspendedAt(status == Tenant.Status.SUSPENDED ? Instant.now() : null);
        if (status == Tenant.Status.ACTIVE) {
            tenant.setTrialEndsAt(null);
        }
        changed(slug);
        return tenant;
    }

    /** Soft delete: the row stays for audit, its connector overrides go. */
    @Transactional
    public void delete(String slug) {
        Tenant tenant = get(slug);
        tenant.setStatus(Tenant.Status.DELETED);
        tenant.setDeletedAt(Instant.now());
        connectorConfigs.deleteScope(ConfigScope.tenant(slug));
        changed(slug);
    }

    /** Replace all feature overrides; a null value removes the override (inherit the plan). */
    @Transactional
    public Tenant setFeatureOverrides(String slug, Map<String, Boolean> overrides) {
        Tenant tenant = get(slug);
        tenant.getFeatureOverrides().clear();
        tenants.saveAndFlush(tenant);
        overrides.forEach((id, enabled) -> {
            Feature feature = Feature.fromId(id).orElseThrow(() -> DomainException.badRequest("Unknown feature: " + id));
            if (!feature.isToggleable()) {
                throw DomainException.badRequest("Feature '" + id + "' cannot be toggled (" + feature.getStatus() + ")");
            }
            if (enabled == null) {
                return;
            }
            TenantFeatureOverride override = new TenantFeatureOverride();
            override.setTenant(tenant);
            override.setFeatureId(feature.getId());
            override.setEnabled(enabled);
            override.setUpdatedAt(Instant.now());
            tenant.getFeatureOverrides().add(override);
        });
        changed(slug);
        return tenant;
    }

    /** Auth config with the JWT secret masked. */
    @Transactional(readOnly = true)
    public TenantConfig.Auth getAuth(String slug) {
        return masked(get(slug).storedAuth());
    }

    @Transactional
    public TenantConfig.Auth setAuth(String slug, TenantConfig.Auth input) {
        Tenant tenant = get(slug);
        TenantConfig.Auth current = tenant.storedAuth();
        TenantConfig.External in = input.external() != null ? input.external() : TenantConfig.External.NONE;
        ExternalAuthType type = in.typeOrNone();
        if (type != ExternalAuthType.NONE && !snapshot(tenant).has(Feature.EXTERNAL_AUTH)) {
            throw DomainException.featureDisabled(Feature.EXTERNAL_AUTH.getId());
        }
        if (type == ExternalAuthType.FIREBASE && blank(in.firebaseProjectId())) {
            throw DomainException.badRequest("firebaseProjectId is required for Firebase auth");
        }
        String secret = in.jwtSecret();
        if (ConnectorConfigService.MASK.equals(secret)) {
            secret = current.external().jwtSecret();
        } else if (!blank(secret)) {
            if (secret.length() < MIN_HS256_SECRET_LENGTH) {
                throw DomainException.badRequest("jwtSecret must be at least " + MIN_HS256_SECRET_LENGTH + " characters (HS256)");
            }
            secret = cipher.encrypt(secret);
        } else {
            secret = null;
        }
        if (!blank(in.jwksUrl())) {
            urlPolicy.check(in.jwksUrl());
        }
        if (type == ExternalAuthType.JWT && secret == null && blank(in.jwksUrl())) {
            throw DomainException.badRequest("JWT auth needs either jwksUrl (RS256/ES256) or jwtSecret (HS256)");
        }
        Integer ttl = input.tokenTtlMinutes();
        if (ttl != null && (ttl < MIN_TOKEN_TTL_MINUTES || ttl > MAX_TOKEN_TTL_MINUTES)) {
            throw DomainException.badRequest("tokenTtlMinutes must be between " + MIN_TOKEN_TTL_MINUTES + " and "
                    + MAX_TOKEN_TTL_MINUTES);
        }
        TenantConfig.External stored = new TenantConfig.External(type, blankToNull(in.firebaseProjectId()),
                blankToNull(in.jwtIssuer()), blankToNull(in.jwtAudience()), blankToNull(in.jwksUrl()), secret,
                defaultIfBlank(in.userIdClaim(), "sub"), defaultIfBlank(in.nameClaim(), "name"),
                defaultIfBlank(in.emailClaim(), "email"));
        TenantConfig.Auth updated = new TenantConfig.Auth(
                input.devLoginEnabled() != null ? input.devLoginEnabled() : current.devLoginEnabled(),
                ttl != null ? ttl : current.tokenTtlMinutes(), stored);
        tenant.setAuth(updated);
        changed(slug);
        return masked(updated);
    }

    /** Fresh snapshot of a loaded tenant (bypasses the directory cache). */
    @Transactional(readOnly = true)
    public TenantSnapshot snapshot(Tenant tenant) {
        return directory.snapshot(tenant, plans.findByCode(tenant.getPlanCode()).orElse(null));
    }

    public FeatureSource featureSource(Feature feature, Tenant tenant) {
        return TenantDirectory.sourceOf(feature, tenant);
    }

    /** Trials that ran out are moved to the default plan rather than cut off. */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT1M")
    @Transactional
    public void expireTrials() {
        Plan fallback = plans.findFirstByDefaultPlanTrue().orElse(null);
        for (Tenant tenant : tenants.findExpiredTrials()) {
            tenant.setStatus(Tenant.Status.ACTIVE);
            tenant.setTrialEndsAt(null);
            if (fallback != null) {
                tenant.setPlanCode(fallback.getCode());
            }
            changed(tenant.getSlug());
            log.info("Trial expired for tenant {}; moved to plan {}", tenant.getSlug(), tenant.getPlanCode());
        }
    }

    // ------------------------------------------------------------------ helpers

    private void changed(String slug) {
        events.publishEvent(new TenantChangedEvent(slug));
    }

    private static TenantConfig.Auth masked(TenantConfig.Auth auth) {
        TenantConfig.External e = auth.external();
        return auth.withExternal(e.withJwtSecret(e.jwtSecret() != null ? ConnectorConfigService.MASK : null));
    }

    private Plan activePlan(String code) {
        Plan plan = plans.findByCode(code).orElseThrow(() -> DomainException.badRequest("Unknown plan: " + code));
        if (!plan.isActive()) {
            throw DomainException.badRequest("Plan '" + code + "' is not active");
        }
        return plan;
    }

    private String uniqueSlug(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (base.length() < 2) {
            base = "tenant-" + base;
        }
        base = base.length() > 40 ? base.substring(0, 40).replaceAll("-+$", "") : base;
        String slug = base;
        for (int i = 2; tenants.existsBySlug(slug); i++) {
            slug = base + "-" + i;
        }
        return slug;
    }

    private static void validateSettings(TenantConfig.Settings s) {
        if (s.maxMessageLength() != null && (s.maxMessageLength() < 1 || s.maxMessageLength() > MAX_MESSAGE_LENGTH)) {
            throw DomainException.badRequest("maxMessageLength must be between 1 and " + MAX_MESSAGE_LENGTH);
        }
        if (s.editWindowMinutes() != null && s.editWindowMinutes() < 0) {
            throw DomainException.badRequest("editWindowMinutes cannot be negative");
        }
        if (s.maxFileSizeMb() != null && (s.maxFileSizeMb() < 1 || s.maxFileSizeMb() > MAX_FILE_SIZE_MB)) {
            throw DomainException.badRequest("maxFileSizeMb must be between 1 and " + MAX_FILE_SIZE_MB);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return blank(s) ? null : s.trim();
    }

    private static String defaultIfBlank(String s, String fallback) {
        return blank(s) ? fallback : s.trim();
    }
}
