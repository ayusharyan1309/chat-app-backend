package com.ayush.chat.admin.dto.response;

import com.ayush.chat.tenant.entity.Tenant;

import java.time.Instant;

public record TenantSummary(String slug, String name, String contactEmail, String domain, Tenant.Status status,
                            String planCode, String planName, String storeProvider, long messagesToday,
                            int connections, Instant createdAt, Instant trialEndsAt) {
}
