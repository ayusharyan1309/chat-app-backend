package com.ayush.chat.audit.model;

/** Every audited action. The ids are stored and filtered on, so they must stay stable. */
public enum AuditAction {
    ADMIN_CREATED("admin.created", "admin"),
    ADMIN_DELETED("admin.deleted", "admin"),
    ADMIN_PASSWORD_CHANGED("admin.password_changed", "admin"),
    PLATFORM_CONNECTOR_UPDATED("infrastructure.updated", "connector"),
    PLATFORM_CONNECTOR_RESET("infrastructure.reset", "connector"),
    PLAN_CREATED("plan.created", "plan"),
    PLAN_UPDATED("plan.updated", "plan"),
    PLAN_DELETED("plan.deleted", "plan"),
    TENANT_CREATED("tenant.created", "tenant"),
    TENANT_UPDATED("tenant.updated", "tenant"),
    TENANT_SUSPENDED("tenant.suspended", "tenant"),
    TENANT_ACTIVATED("tenant.activated", "tenant"),
    TENANT_DELETED("tenant.deleted", "tenant"),
    TENANT_FEATURES_UPDATED("tenant.features_updated", "tenant"),
    TENANT_AUTH_UPDATED("tenant.auth_updated", "tenant"),
    TENANT_CONNECTOR_UPDATED("tenant.connector_updated", "connector"),
    TENANT_CONNECTOR_RESET("tenant.connector_reset", "connector"),
    API_KEY_CREATED("api_key.created", "api_key"),
    API_KEY_REVOKED("api_key.revoked", "api_key"),
    WEBHOOK_CREATED("webhook.created", "webhook"),
    WEBHOOK_UPDATED("webhook.updated", "webhook"),
    WEBHOOK_DELETED("webhook.deleted", "webhook"),
    USER_UPSERTED("user.upserted", "user"),
    USER_DEACTIVATED("user.deactivated", "user"),
    USER_IMPERSONATED("user.impersonated", "user");

    private final String id;
    private final String targetType;

    AuditAction(String id, String targetType) {
        this.id = id;
        this.targetType = targetType;
    }

    public String id() {
        return id;
    }

    public String targetType() {
        return targetType;
    }
}
