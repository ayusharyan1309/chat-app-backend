package com.ayush.chat.admin.dto.request;

public record CreateTenantRequest(String name, String slug, String contactEmail, String domain, String planCode, Integer trialDays) {
}
