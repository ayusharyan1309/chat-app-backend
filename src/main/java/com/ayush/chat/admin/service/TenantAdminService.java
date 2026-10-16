package com.ayush.chat.admin.service;

import com.ayush.chat.admin.dto.request.ConnectorRequest;
import com.ayush.chat.admin.dto.request.CreateTenantRequest;
import com.ayush.chat.admin.dto.response.ApiKeyView;
import com.ayush.chat.admin.dto.response.ConnectorOverrideView;
import com.ayush.chat.admin.dto.response.CreatedTenantResponse;
import com.ayush.chat.admin.dto.response.FeatureState;
import com.ayush.chat.admin.dto.response.ImpersonationTokenResponse;
import com.ayush.chat.admin.dto.response.TenantDetail;
import com.ayush.chat.audit.model.AuditAction;
import com.ayush.chat.audit.service.AuditService;
import com.ayush.chat.auth.dto.CreatedApiKey;
import com.ayush.chat.auth.dto.IssuedToken;
import com.ayush.chat.auth.entity.ApiKey;
import com.ayush.chat.auth.service.ApiKeyService;
import com.ayush.chat.auth.service.UserTokenService;
import com.ayush.chat.chat.dto.request.UpsertUserRequest;
import com.ayush.chat.chat.dto.response.UserView;
import com.ayush.chat.chat.service.UserDirectoryService;
import com.ayush.chat.core.connector.ConnectorConfig;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorRegistry;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.connector.config.ConfigScope;
import com.ayush.chat.core.connector.config.ConnectorConfigService;
import com.ayush.chat.core.connector.runtime.TenantConnectors;
import com.ayush.chat.core.exception.DomainException;
import com.ayush.chat.core.util.AfterCommit;
import com.ayush.chat.core.util.Json;
import com.ayush.chat.tenant.dto.CreateTenantCommand;
import com.ayush.chat.tenant.dto.UpdateTenantRequest;
import com.ayush.chat.tenant.entity.Tenant;
import com.ayush.chat.tenant.model.TenantConfig;
import com.ayush.chat.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Operator actions on tenants. Each method is one transaction and records exactly one audit
 * entry, so an action that fails leaves neither a partial change nor an audit trail behind.
 */
@Service
@RequiredArgsConstructor
public class TenantAdminService {

    private static final String DEFAULT_KEY_NAME = "Default server key";

    private final TenantService tenants;
    private final TenantQueryService queries;
    private final ApiKeyService apiKeys;
    private final UserDirectoryService users;
    private final UserTokenService tokens;
    private final TenantConnectors connectors;
    private final ConnectorConfigService connectorConfigs;
    private final ConnectorRegistry registry;
    private final AuditService audit;

    // ------------------------------------------------------------------ lifecycle

    /** Tenant and its first server key are created together or not at all. */
    @Transactional
    public CreatedTenantResponse create(CreateTenantRequest request) {
        Tenant tenant = tenants.create(new CreateTenantCommand(request.name(), request.slug(),
                request.contactEmail(), request.domain(), request.planCode(), request.trialDays()));
        CreatedApiKey key = apiKeys.create(tenant.getSlug(), DEFAULT_KEY_NAME, AuditService.currentActor());
        audit.record(AuditAction.TENANT_CREATED, tenant.getSlug(), tenant.getSlug(), "plan=" + tenant.getPlanCode());
        return new CreatedTenantResponse(queries.detail(tenant.getSlug()), ApiKeyView.of(key.key(), key.plaintext()));
    }

    @Transactional
    public TenantDetail update(String slug, UpdateTenantRequest update) {
        tenants.update(slug, update);
        audit.record(AuditAction.TENANT_UPDATED, slug, slug, Json.write(update));
        return queries.detail(slug);
    }

    @Transactional
    public TenantDetail suspend(String slug) {
        tenants.setStatus(slug, Tenant.Status.SUSPENDED);
        audit.record(AuditAction.TENANT_SUSPENDED, slug, slug, null);
        return queries.detail(slug);
    }

    @Transactional
    public TenantDetail activate(String slug) {
        tenants.setStatus(slug, Tenant.Status.ACTIVE);
        audit.record(AuditAction.TENANT_ACTIVATED, slug, slug, null);
        return queries.detail(slug);
    }

    /** Soft-deletes the tenant, revokes its keys and, after commit, closes its dedicated connectors. */
    @Transactional
    public void delete(String slug) {
        tenants.get(slug);
        apiKeys.revokeAll(slug);
        tenants.delete(slug);
        audit.record(AuditAction.TENANT_DELETED, slug, slug, null);
        AfterCommit.run(() -> {
            connectors.router(ConnectorKind.MESSAGE_STORE).invalidate(slug);
            connectors.router(ConnectorKind.PUSH).invalidate(slug);
        });
    }

    @Transactional
    public List<FeatureState> setFeatures(String slug, Map<String, Boolean> overrides) {
        tenants.setFeatureOverrides(slug, overrides == null ? Map.of() : overrides);
        audit.record(AuditAction.TENANT_FEATURES_UPDATED, slug, slug, Json.write(overrides));
        return queries.features(slug);
    }

    @Transactional
    public TenantConfig.Auth setAuth(String slug, TenantConfig.Auth request) {
        TenantConfig.Auth result = tenants.setAuth(slug, request);
        audit.record(AuditAction.TENANT_AUTH_UPDATED, slug, slug,
                "external=" + result.external().typeOrNone().id() + ", devLogin=" + result.devLoginEnabled());
        return result;
    }

    // ------------------------------------------------------------------ dedicated connectors

    public ConnectorOverrideView connector(String slug, String kind) {
        tenants.get(slug);
        return connectorView(slug, overridable(kind));
    }

    public ConnectorOverrideView applyConnector(String slug, String kind, ConnectorRequest request) {
        tenants.get(slug);
        ConnectorKind k = overridable(kind);
        ConnectorConfig config = connectorConfigs.unmask(ConfigScope.tenant(slug), k, request.provider(), request.settings());
        connectors.router(k).apply(slug, config, AuditService.currentActor());
        audit.record(AuditAction.TENANT_CONNECTOR_UPDATED, k.slug(), slug, "provider=" + request.provider());
        return connectorView(slug, k);
    }

    public HealthStatus testConnector(String slug, String kind, ConnectorRequest request) {
        tenants.get(slug);
        ConnectorKind k = overridable(kind);
        return registry.test(connectorConfigs.unmask(ConfigScope.tenant(slug), k, request.provider(), request.settings()));
    }

    public ConnectorOverrideView resetConnector(String slug, String kind) {
        tenants.get(slug);
        ConnectorKind k = overridable(kind);
        connectorConfigs.delete(ConfigScope.tenant(slug), k);
        connectors.router(k).invalidate(slug);
        audit.record(AuditAction.TENANT_CONNECTOR_RESET, k.slug(), slug, "inherit platform default");
        return connectorView(slug, k);
    }

    private ConnectorOverrideView connectorView(String slug, ConnectorKind kind) {
        return connectorConfigs.tenantOverride(slug, kind)
                .map(config -> new ConnectorOverrideView(false, config.provider(), connectorConfigs.mask(config),
                        connectors.router(kind).health(slug)))
                .orElseGet(ConnectorOverrideView::inheritedView);
    }

    private static ConnectorKind overridable(String slug) {
        ConnectorKind kind = ConnectorKind.fromSlug(slug);
        if (!kind.isTenantOverridable()) {
            throw DomainException.badRequest(kind.getDisplayName() + " is platform-wide and cannot be overridden per tenant");
        }
        return kind;
    }

    // ------------------------------------------------------------------ API keys

    public List<ApiKeyView> apiKeys(String slug) {
        tenants.get(slug);
        return apiKeys.list(slug).stream().map(k -> ApiKeyView.of(k, null)).toList();
    }

    @Transactional
    public ApiKeyView createApiKey(String slug, String name) {
        tenants.get(slug);
        CreatedApiKey key = apiKeys.create(slug, name, AuditService.currentActor());
        audit.record(AuditAction.API_KEY_CREATED, key.key().getKeyPrefix(), slug, name);
        return ApiKeyView.of(key.key(), key.plaintext());
    }

    @Transactional
    public void revokeApiKey(String slug, Long id) {
        ApiKey key = apiKeys.revoke(slug, id);
        audit.record(AuditAction.API_KEY_REVOKED, String.valueOf(key.getId()), slug, key.getName());
    }

    // ------------------------------------------------------------------ end users

    public UserView upsertUser(String slug, UpsertUserRequest input) {
        UserView user = users.upsert(slug, input);
        audit.record(AuditAction.USER_UPSERTED, user.id(), slug, null);
        return user;
    }

    /** Short-lived token to act as a user (dashboard playground). Always audited. */
    public ImpersonationTokenResponse impersonate(String slug, String userId) {
        tenants.get(slug);
        IssuedToken token = tokens.impersonate(slug, userId);
        audit.record(AuditAction.USER_IMPERSONATED, userId, slug, "token valid until " + token.expiresAt());
        return new ImpersonationTokenResponse(token.token(), token.expiresAt(), users.get(slug, userId));
    }
}
