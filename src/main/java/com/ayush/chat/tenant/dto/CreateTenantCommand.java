package com.ayush.chat.tenant.dto;

public record CreateTenantCommand(String name, String slug, String contactEmail, String domain, String planCode, Integer trialDays) {
}
