package com.ayush.chat.tenant.dto;

import com.ayush.chat.tenant.model.TenantLimits;

import java.math.BigDecimal;
import java.util.Set;

public record PlanRequest(String code, String name, String description, BigDecimal priceMonthly, BigDecimal priceYearly,
                          String currency, TenantLimits limits, Set<String> features, Boolean isDefault, Boolean active,
                          Integer sortOrder) {
}
