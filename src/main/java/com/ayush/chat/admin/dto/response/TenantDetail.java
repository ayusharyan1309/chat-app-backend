package com.ayush.chat.admin.dto.response;

import com.ayush.chat.tenant.entity.Tenant;
import com.ayush.chat.tenant.model.TenantConfig;
import com.ayush.chat.tenant.model.TenantLimits;

import java.time.Instant;
import java.util.List;

public record TenantDetail(String slug, String name, String contactEmail, String domain, Tenant.Status status,
                           String planCode, String planName, String storeProvider, long messagesToday, int connections,
                           Instant createdAt, Instant trialEndsAt, Instant updatedAt,
                           TenantConfig.Branding branding, TenantConfig.Settings settings, TenantLimits limits,
                           TenantLimits limitOverrides, List<FeatureState> features, TenantStats stats) {
}
