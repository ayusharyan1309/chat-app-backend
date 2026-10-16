package com.ayush.chat.chat.dto.response;

import com.ayush.chat.tenant.model.TenantConfig;

import java.util.List;

/** What a client app needs before sign-in: branding, enabled features, auth options and limits. */
public record TenantClientConfig(String tenant, String name, TenantConfig.Branding branding, List<String> features,
                                 ClientAuthOptions auth, TenantConfig.Settings settings) {
}
