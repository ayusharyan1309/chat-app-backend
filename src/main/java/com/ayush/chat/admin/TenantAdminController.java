package com.ayush.chat.admin;

import com.ayush.chat.audit.AuditService;
import com.ayush.chat.auth.ApiKey;
import com.ayush.chat.auth.ApiKeyService;
import com.ayush.chat.auth.JwtService;
import com.ayush.chat.chat.ChatService;
import com.ayush.chat.chat.Views.UserView;
import com.ayush.chat.chat.webhook.WebhookDelivery;
import com.ayush.chat.chat.webhook.WebhookService;
import com.ayush.chat.core.cluster.ClusterService;
import com.ayush.chat.core.connector.config.ConfigScope;
import com.ayush.chat.core.connector.config.ConnectorConfigService;
import com.ayush.chat.core.connector.runtime.TenantConnectorRouter;
import com.ayush.chat.core.connector.runtime.TenantConnectors;
import com.ayush.chat.core.connector.ConnectorConfig;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorRegistry;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.core.util.Json;
import com.ayush.chat.store.model.StoreStats;
import com.ayush.chat.tenant.Feature;
import com.ayush.chat.tenant.Plan;
import com.ayush.chat.tenant.Tenant;
import com.ayush.chat.tenant.TenantConfig;
import com.ayush.chat.tenant.TenantLimits;
import com.ayush.chat.tenant.TenantService;
import com.ayush.chat.tenant.TenantSnapshot;
import com.ayush.chat.tenant.UsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin/tenants")
@RequiredArgsConstructor
public class TenantAdminController {

    private final TenantService tenantService;
    private final ApiKeyService apiKeys;
    private final ChatService chat;
    private final JwtService jwt;
    private final WebhookService webhooks;
    private final TenantConnectors connectors;
    private final ConnectorConfigService configService;
    private final ConnectorRegistry registry;
    private final UsageService usage;
    private final ClusterService cluster;
    private final AuditService audit;

    // ------------------------------------------------------------------ DTOs

    public record CreateTenant(String name, String slug, String contactEmail, String domain, String planCode, Integer trialDays) {
    }

    public record TenantSummary(String slug, String name, String contactEmail, String domain, Tenant.Status status,
                                String planCode, String planName, String storeProvider, long messagesToday,
                                int connections, Instant createdAt, Instant trialEndsAt) {
    }

    public record FeatureState(String id, String name, String category, Feature.Status status, boolean enabled, String source) {
    }

    public record Stats(long users, long conversations, long messages, long messagesToday, int connections, long storageBytes) {
    }

    public record TenantDetail(String slug, String name, String contactEmail, String domain, Tenant.Status status,
                               String planCode, String planName, String storeProvider, long messagesToday, int connections,
                               Instant createdAt, Instant trialEndsAt, Instant updatedAt,
                               TenantConfig.Branding branding, TenantConfig.Settings settings, TenantLimits limits,
                               TenantLimits limitOverrides, List<FeatureState> features, Stats stats) {
    }

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

    public record NamedRequest(String name) {
    }

    public record TokenView(String token, Instant expiresAt, UserView user) {
    }

    // ------------------------------------------------------------------ tenants

    @GetMapping
    public List<TenantSummary> list(@RequestParam(required = false) String q, @RequestParam(required = false) String status) {
        Tenant.Status filter = status == null || status.isBlank() ? null : Tenant.Status.valueOf(status.toUpperCase());
        Map<String, String> planNames = new LinkedHashMap<>();
        return tenantService.list(q, filter).stream().map(t -> summary(t, planNames.computeIfAbsent(t.getPlanCode(),
                code -> tenantService.planOf(t).map(Plan::getName).orElse(code)))).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedTenant create(@RequestBody CreateTenant request) {
        Tenant tenant = tenantService.create(new TenantService.TenantInput(request.name(), request.slug(),
                request.contactEmail(), request.domain(), request.planCode(), request.trialDays()));
        ApiKeyService.CreatedKey key = apiKeys.create(tenant, "Default server key", AuditService.currentActor());
        audit.record("tenant.created", "tenant", tenant.getSlug(), tenant.getSlug(), "plan=" + tenant.getPlanCode());
        return new CreatedTenant(detail(tenant.getSlug()), ApiKeyView.of(key.key(), key.plaintext()));
    }

    @GetMapping("/{slug}")
    public TenantDetail get(@PathVariable String slug) {
        return detail(slug);
    }

    @PutMapping("/{slug}")
    public TenantDetail update(@PathVariable String slug, @RequestBody TenantService.TenantUpdate update) {
        tenantService.update(slug, update);
        audit.record("tenant.updated", "tenant", slug, slug, Json.write(update));
        return detail(slug);
    }

    @PostMapping("/{slug}/suspend")
    public TenantDetail suspend(@PathVariable String slug) {
        tenantService.setStatus(slug, Tenant.Status.SUSPENDED);
        audit.record("tenant.suspended", "tenant", slug, slug, null);
        return detail(slug);
    }

    @PostMapping("/{slug}/activate")
    public TenantDetail activate(@PathVariable String slug) {
        tenantService.setStatus(slug, Tenant.Status.ACTIVE);
        audit.record("tenant.activated", "tenant", slug, slug, null);
        return detail(slug);
    }

    @DeleteMapping("/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String slug) {
        Tenant tenant = tenantService.get(slug);
        apiKeys.revokeAll(tenant);
        tenantService.delete(slug);
        connectors.router(ConnectorKind.MESSAGE_STORE).invalidate(slug);
        connectors.router(ConnectorKind.PUSH).invalidate(slug);
        audit.record("tenant.deleted", "tenant", slug, slug, null);
    }

    @PutMapping("/{slug}/features")
    public List<FeatureState> features(@PathVariable String slug, @RequestBody FeatureOverrides request) {
        Tenant tenant = tenantService.setFeatureOverrides(slug, request.overrides() == null ? Map.of() : request.overrides());
        audit.record("tenant.features_updated", "tenant", slug, slug, Json.write(request.overrides()));
        return features(tenant);
    }

    @GetMapping("/{slug}/usage")
    public List<UsageService.DailyUsage> usage(@PathVariable String slug, @RequestParam(defaultValue = "30") int days) {
        tenantService.get(slug);
        return usage.history(slug, days);
    }

    // ------------------------------------------------------------------ connector overrides

    @GetMapping("/{slug}/connectors/{kind}")
    public Map<String, Object> connector(@PathVariable String slug, @PathVariable String kind) {
        tenantService.get(slug);
        return connectorState(slug, overridable(kind));
    }

    @PutMapping("/{slug}/connectors/{kind}")
    public Map<String, Object> applyConnector(@PathVariable String slug, @PathVariable String kind,
                                              @RequestBody ConnectorRequest request) {
        tenantService.get(slug);
        ConnectorKind k = overridable(kind);
        ConnectorConfig config = configService.unmask(ConfigScope.tenant(slug), k, request.provider(),
                request.settings());
        connectors.router(k).apply(slug, config, AuditService.currentActor());
        audit.record("tenant.connector_updated", "connector", k.slug(), slug, "provider=" + request.provider());
        return connectorState(slug, k);
    }

    @PostMapping("/{slug}/connectors/{kind}/test")
    public HealthStatus testConnector(@PathVariable String slug, @PathVariable String kind, @RequestBody ConnectorRequest request) {
        tenantService.get(slug);
        ConnectorKind k = overridable(kind);
        return registry.test(configService.unmask(ConfigScope.tenant(slug), k, request.provider(),
                request.settings()));
    }

    @DeleteMapping("/{slug}/connectors/{kind}")
    public Map<String, Object> inheritConnector(@PathVariable String slug, @PathVariable String kind) {
        tenantService.get(slug);
        ConnectorKind k = overridable(kind);
        configService.delete(ConfigScope.tenant(slug), k);
        connectors.router(k).invalidate(slug);
        audit.record("tenant.connector_reset", "connector", k.slug(), slug, "inherit platform default");
        return connectorState(slug, k);
    }

    private Map<String, Object> connectorState(String slug, ConnectorKind kind) {
        Optional<ConnectorConfig> override = configService.tenantOverride(slug, kind);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inherited", override.isEmpty());
        override.ifPresent(config -> {
            out.put("provider", config.provider());
            out.put("settings", configService.mask(config));
            TenantConnectorRouter<?> router = connectors.router(kind);
            out.put("health", router.health(slug));
        });
        return out;
    }

    private static ConnectorKind overridable(String slug) {
        ConnectorKind kind = ConnectorKind.fromSlug(slug);
        if (!kind.isTenantOverridable()) {
            throw DomainException.badRequest(kind.getDisplayName() + " is platform-wide and cannot be overridden per tenant");
        }
        return kind;
    }

    // ------------------------------------------------------------------ auth

    @GetMapping("/{slug}/auth")
    public TenantConfig.Auth auth(@PathVariable String slug) {
        return tenantService.getAuth(slug);
    }

    @PutMapping("/{slug}/auth")
    public TenantConfig.Auth updateAuth(@PathVariable String slug, @RequestBody TenantConfig.Auth request) {
        TenantConfig.Auth result = tenantService.setAuth(slug, request);
        audit.record("tenant.auth_updated", "tenant", slug, slug, "external=" + result.external().typeOrNone()
                + ", devLogin=" + result.devLoginEnabled());
        return result;
    }

    // ------------------------------------------------------------------ API keys

    @GetMapping("/{slug}/api-keys")
    public List<ApiKeyView> apiKeys(@PathVariable String slug) {
        return apiKeys.list(tenantService.get(slug)).stream().map(k -> ApiKeyView.of(k, null)).toList();
    }

    @PostMapping("/{slug}/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyView createApiKey(@PathVariable String slug, @RequestBody NamedRequest request) {
        ApiKeyService.CreatedKey key = apiKeys.create(tenantService.get(slug), request.name(), AuditService.currentActor());
        audit.record("api_key.created", "api_key", key.key().getKeyPrefix(), slug, request.name());
        return ApiKeyView.of(key.key(), key.plaintext());
    }

    @DeleteMapping("/{slug}/api-keys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeApiKey(@PathVariable String slug, @PathVariable Long id) {
        apiKeys.revoke(tenantService.get(slug), id);
        audit.record("api_key.revoked", "api_key", String.valueOf(id), slug, null);
    }

    // ------------------------------------------------------------------ webhooks

    @GetMapping("/{slug}/webhooks")
    public List<WebhookService.WebhookView> webhooks(@PathVariable String slug) {
        return webhooks.list(tenantService.get(slug));
    }

    @PostMapping("/{slug}/webhooks")
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookService.WebhookView createWebhook(@PathVariable String slug, @RequestBody WebhookService.WebhookInput input) {
        WebhookService.WebhookView view = webhooks.create(tenantService.get(slug), input);
        audit.record("webhook.created", "webhook", String.valueOf(view.id()), slug, view.url());
        return view;
    }

    @PutMapping("/{slug}/webhooks/{id}")
    public WebhookService.WebhookView updateWebhook(@PathVariable String slug, @PathVariable Long id,
                                                    @RequestBody WebhookService.WebhookInput input) {
        WebhookService.WebhookView view = webhooks.update(tenantService.get(slug), id, input);
        audit.record("webhook.updated", "webhook", String.valueOf(id), slug, view.url());
        return view;
    }

    @DeleteMapping("/{slug}/webhooks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWebhook(@PathVariable String slug, @PathVariable Long id) {
        webhooks.delete(tenantService.get(slug), id);
        audit.record("webhook.deleted", "webhook", String.valueOf(id), slug, null);
    }

    @PostMapping("/{slug}/webhooks/{id}/test")
    public WebhookDelivery testWebhook(@PathVariable String slug, @PathVariable Long id) {
        return webhooks.test(tenantService.get(slug), id);
    }

    @GetMapping("/{slug}/webhooks/{id}/deliveries")
    public List<WebhookDelivery> deliveries(@PathVariable String slug, @PathVariable Long id) {
        return webhooks.deliveries(tenantService.get(slug), id);
    }

    // ------------------------------------------------------------------ end users

    @GetMapping("/{slug}/users")
    public Map<String, Object> users(@PathVariable String slug, @RequestParam(required = false) String q,
                                     @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        tenantService.get(slug);
        return Map.of("items", chat.searchUsers(slug, null, q, offset, limit, true), "total", chat.countUsers(slug));
    }

    @PostMapping("/{slug}/users")
    public UserView upsertUser(@PathVariable String slug, @RequestBody ChatService.UserInput input) {
        UserView user = chat.upsertUser(slug, input);
        audit.record("user.upserted", "user", user.id(), slug, null);
        return user;
    }

    /** Impersonation token for testing (e.g. the dashboard playground). Always audited. */
    @PostMapping("/{slug}/users/{userId}/token")
    public TokenView userToken(@PathVariable String slug, @PathVariable String userId) {
        tenantService.get(slug);
        chat.requireActiveUser(slug, userId);
        JwtService.IssuedToken token = jwt.issueUser(slug, userId, Duration.ofHours(8));
        audit.record("user.impersonated", "user", userId, slug, "8h token issued from the dashboard");
        return new TokenView(token.token(), token.expiresAt(), chat.getUser(slug, userId));
    }

    // ------------------------------------------------------------------ mapping

    private TenantSummary summary(Tenant t, String planName) {
        return new TenantSummary(t.getSlug(), t.getName(), t.getContactEmail(), t.getDomain(), t.getStatus(), t.getPlanCode(),
                planName, storeProvider(t.getSlug()), safeMessagesToday(t.getSlug()), cluster.connections(t.getSlug()),
                t.getCreatedAt(), t.getTrialEndsAt());
    }

    private TenantDetail detail(String slug) {
        Tenant t = tenantService.get(slug);
        TenantSnapshot snapshot = tenantService.snapshot(t);
        TenantLimits overrides = Optional.ofNullable(Json.read(t.getLimitsJson(), TenantLimits.class)).orElse(TenantLimits.NONE);
        StoreStats stats;
        try {
            stats = connectors.store(slug).stats(slug);
        } catch (Exception e) {
            stats = new StoreStats(-1, -1, -1);
        }
        long today = safeMessagesToday(slug);
        int conns = cluster.connections(slug);
        return new TenantDetail(t.getSlug(), t.getName(), t.getContactEmail(), t.getDomain(), t.getStatus(), t.getPlanCode(),
                snapshot.planName(), storeProvider(slug), today, conns, t.getCreatedAt(), t.getTrialEndsAt(), t.getUpdatedAt(),
                snapshot.branding(), snapshot.settings(), snapshot.limits(), overrides, features(t),
                new Stats(stats.users(), stats.conversations(), stats.messages(), today, conns, usage.storedBytes(slug)));
    }

    private List<FeatureState> features(Tenant tenant) {
        TenantSnapshot snapshot = tenantService.snapshot(tenant);
        return Arrays.stream(Feature.values())
                .map(f -> new FeatureState(f.getId(), f.getDisplayName(), f.getCategory(), f.getStatus(), snapshot.has(f)
                        && f.getStatus() != Feature.Status.PLANNED, tenantService.featureSource(f, tenant)))
                .toList();
    }

    private String storeProvider(String slug) {
        return configService.tenantOverride(slug, ConnectorKind.MESSAGE_STORE).map(ConnectorConfig::provider).orElse(null);
    }

    private long safeMessagesToday(String slug) {
        try {
            return usage.messagesToday(slug);
        } catch (Exception e) {
            return 0;
        }
    }
}
