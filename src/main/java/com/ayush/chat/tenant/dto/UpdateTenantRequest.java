package com.ayush.chat.tenant.dto;

import com.ayush.chat.tenant.model.TenantConfig;
import com.ayush.chat.tenant.model.TenantLimits;

public record UpdateTenantRequest(String name, String contactEmail, String domain, String planCode,
                                  TenantConfig.Branding branding, TenantConfig.Settings settings, TenantLimits limitOverrides) {
}
