package com.ayush.chat.tenant;

import com.ayush.chat.core.connector.config.ConfigScope;
import com.ayush.chat.core.connector.config.ConnectorConfigService;
import com.ayush.chat.core.crypto.SecretCipher;
import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.core.net.OutboundUrlPolicy;
import com.ayush.chat.core.util.Json;
import com.ayush.chat.tenant.TenantRepositories.PlanRepository;
import com.ayush.chat.tenant.TenantRepositories.TenantRepository;
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
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Tenant lifecycle and configuration (admin operations). Every mutation invalidates the
 * {@link TenantDirectory} cache cluster-wide via {@link TenantChangedEvent}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,47}$");

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

    /** Fired after any tenant change; listeners invalidate caches and broadcast to other nodes. */
    public record TenantChangedEvent(String slug) {
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

    @Transactional(readOnly = true)
    public Tenant get(String slug) {
        Tenant tenant = tenants.findBySlugWithFeatures(slug)
                .filter(t -> t.getStatus() != Tenant.Status.DELETED)
                .orElseThrow(() -> DomainException.notFound("Tenant '" + slug + "'"));
        tenant.getFeatureOverrides().size();
        return tenant;
    }

    @Transactional(readOnly = true)
    public Optional<Plan> planOf(Tenant tenant) {
        return plans.findByCode(tenant.getPlanCode());
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
        tenant.setContactEmail(input.contactEmail());
        tenant.setDomain(input.domain());
        tenant.setPlanCode(plan.getCode());
        if (input.trialDays() != null && input.trialDays() > 0) {
            tenant.setStatus(Tenant.Status.TRIAL);
            tenant.setTrialEndsAt(Instant.now().plus(Duration.ofDays(input.trialDays())));
        } else {
            tenant.setStatus(Tenant.Status.ACTIVE);
        }
        tenant.setBrandingJson(Json.write(new TenantConfig.Branding(input.name().trim(), null,
                TenantConfig.Branding.DEFAULT.primaryColor(), TenantConfig.Branding.DEFAULT.accentColor())));
        tenant.setSettingsJson(Json.write(TenantConfig.Settings.DEFAULT));
        tenant.setAuthJson(Json.write(TenantConfig.Auth.DEFAULT));
        tenants.save(tenant);
        changed(slug);
        log.info("Created tenant {} on plan {}", slug, plan.getCode());
        return tenant;
    }

    @Transactional
    public Tenant update(String slug, TenantUpdate update) {
        Tenant tenant = get(slug);
        if (update.name() != null && !update.name().isBlank()) tenant.setName(update.name().trim());
        if (update.contactEmail() != null) tenant.setContactEmail(blankToNull(update.contactEmail()));
        if (update.domain() != null) tenant.setDomain(blankToNull(update.domain()));
        if (update.planCode() != null && !update.planCode().equals(tenant.getPlanCode())) {
            tenant.setPlanCode(activePlan(update.planCode()).getCode());
        }
        if (update.branding() != null) tenant.setBrandingJson(Json.write(update.branding()));
        if (update.settings() != null) {
            validateSettings(update.settings());
            tenant.setSettingsJson(Json.write(update.settings()));
        }
        if (update.limitOverrides() != null) tenant.setLimitsJson(Json.write(update.limitOverrides()));
        tenants.save(tenant);
        changed(slug);
        return tenant;
    }

    @Transactional
    public Tenant setStatus(String slug, Tenant.Status status) {
        Tenant tenant = get(slug);
        tenant.setStatus(status);
        tenant.setSuspendedAt(status == Tenant.Status.SUSPENDED ? Instant.now() : null);
        if (status == Tenant.Status.ACTIVE) {
            tenant.setTrialEndsAt(null);
        }
        tenants.save(tenant);
        changed(slug);
        return tenant;
    }

    @Transactional
    public void delete(String slug) {
        Tenant tenant = get(slug);
        tenant.setStatus(Tenant.Status.DELETED);
        tenant.setDeletedAt(Instant.now());
        tenants.save(tenant);
        connectorConfigs.deleteScope(ConfigScope.tenant(slug));
        changed(slug);
    }

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
        tenants.save(tenant);
        changed(slug);
        return tenant;
    }

    /** Auth config with the JWT secret masked. */
    @Transactional(readOnly = true)
    public TenantConfig.Auth getAuth(String slug) {
        TenantConfig.Auth auth = directory.auth(get(slug), false);
        TenantConfig.External e = auth.external();
        TenantConfig.External masked = new TenantConfig.External(e.typeOrNone(), e.firebaseProjectId(), e.jwtIssuer(),
                e.jwtAudience(), e.jwksUrl(), e.jwtSecret() != null ? ConnectorConfigService.MASK : null,
                e.userIdClaim(), e.nameClaim(), e.emailClaim());
        return new TenantConfig.Auth(auth.devLoginEnabled(), auth.tokenTtlMinutes(), masked);
    }

    @Transactional
    public TenantConfig.Auth setAuth(String slug, TenantConfig.Auth input) {
        Tenant tenant = get(slug);
        TenantConfig.Auth current = directory.auth(tenant, false);
        TenantConfig.External in = input.external() != null ? input.external() : TenantConfig.External.NONE;
        String type = in.typeOrNone();
        if (!List.of("none", "firebase", "jwt").contains(type)) {
            throw DomainException.badRequest("external.type must be none, firebase or jwt");
        }
        if (!type.equals("none") && !directory.toSnapshot(tenant).has(Feature.EXTERNAL_AUTH)) {
            throw DomainException.featureDisabled(Feature.EXTERNAL_AUTH.getId());
        }
        if (type.equals("firebase") && blank(in.firebaseProjectId())) {
            throw DomainException.badRequest("firebaseProjectId is required for Firebase auth");
        }
        String secret = in.jwtSecret();
        if (ConnectorConfigService.MASK.equals(secret)) {
            secret = current.external().jwtSecret();
        } else if (!blank(secret)) {
            if (secret.length() < 32) {
                throw DomainException.badRequest("jwtSecret must be at least 32 characters (HS256)");
            }
            secret = cipher.encrypt(secret);
        } else {
            secret = null;
        }
        if (!blank(in.jwksUrl())) {
            urlPolicy.check(in.jwksUrl());
        }
        if (type.equals("jwt") && secret == null && blank(in.jwksUrl())) {
            throw DomainException.badRequest("JWT auth needs either jwksUrl (RS256/ES256) or jwtSecret (HS256)");
        }
        Integer ttl = input.tokenTtlMinutes();
        if (ttl != null && (ttl < 5 || ttl > 60 * 24 * 30)) {
            throw DomainException.badRequest("tokenTtlMinutes must be between 5 and 43200");
        }
        TenantConfig.External stored = new TenantConfig.External(type, blankToNull(in.firebaseProjectId()),
                blankToNull(in.jwtIssuer()), blankToNull(in.jwtAudience()), blankToNull(in.jwksUrl()), secret,
                defaultIfBlank(in.userIdClaim(), "sub"), defaultIfBlank(in.nameClaim(), "name"),
                defaultIfBlank(in.emailClaim(), "email"));
        tenant.setAuthJson(Json.write(new TenantConfig.Auth(
                input.devLoginEnabled() != null ? input.devLoginEnabled() : current.devLoginEnabled(),
                ttl != null ? ttl : current.tokenTtlMinutes(), stored)));
        tenants.save(tenant);
        changed(slug);
        return getAuth(slug);
    }

    public TenantSnapshot snapshot(Tenant tenant) {
        return directory.toSnapshot(tenant);
    }

    public String featureSource(Feature feature, Tenant tenant) {
        return TenantDirectory.sourceOf(feature, plans.findByCode(tenant.getPlanCode()).orElse(null), tenant);
    }

    /** Trials that ran out are moved to the default plan rather than cut off. */
    @Scheduled(fixedDelay = 15 * 60 * 1000, initialDelay = 60 * 1000)
    @Transactional
    public void expireTrials() {
        Plan fallback = plans.findFirstByDefaultPlanTrue().orElse(null);
        for (Tenant tenant : tenants.findExpiredTrials()) {
            tenant.setStatus(Tenant.Status.ACTIVE);
            tenant.setTrialEndsAt(null);
            if (fallback != null) {
                tenant.setPlanCode(fallback.getCode());
            }
            tenants.save(tenant);
            changed(tenant.getSlug());
            log.info("Trial expired for tenant {}; moved to plan {}", tenant.getSlug(), tenant.getPlanCode());
        }
    }

    // ------------------------------------------------------------------ helpers

    private void changed(String slug) {
        directory.invalidate(slug);
        events.publishEvent(new TenantChangedEvent(slug));
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
        if (s.maxMessageLength() != null && (s.maxMessageLength() < 1 || s.maxMessageLength() > 65_536)) {
            throw DomainException.badRequest("maxMessageLength must be between 1 and 65536");
        }
        if (s.editWindowMinutes() != null && s.editWindowMinutes() < 0) {
            throw DomainException.badRequest("editWindowMinutes cannot be negative");
        }
        if (s.maxFileSizeMb() != null && (s.maxFileSizeMb() < 1 || s.maxFileSizeMb() > 2048)) {
            throw DomainException.badRequest("maxFileSizeMb must be between 1 and 2048");
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
