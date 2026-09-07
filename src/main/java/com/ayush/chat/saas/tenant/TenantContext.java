package com.ayush.chat.saas.tenant;

/**
 * TenantContext stores the current tenant information for the current request/thread.
 * This allows the application to be tenant-aware throughout the request lifecycle.
 */
public class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<Long> CURRENT_TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_TENANT_NAME = new ThreadLocal<>();

    public static void setCurrentTenant(String tenantIdentifier) {
        CURRENT_TENANT.set(tenantIdentifier);
    }

    public static String getCurrentTenant() {
        return CURRENT_TENANT.get();
    }

    public static void setCurrentTenantId(Long tenantId) {
        CURRENT_TENANT_ID.set(tenantId);
    }

    public static Long getCurrentTenantId() {
        return CURRENT_TENANT_ID.get();
    }

    public static void setCurrentTenantName(String tenantName) {
        CURRENT_TENANT_NAME.set(tenantName);
    }

    public static String getCurrentTenantName() {
        return CURRENT_TENANT_NAME.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
        CURRENT_TENANT_ID.remove();
        CURRENT_TENANT_NAME.remove();
    }
}
