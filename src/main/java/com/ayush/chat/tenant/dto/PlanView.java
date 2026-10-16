package com.ayush.chat.tenant.dto;

import com.ayush.chat.tenant.entity.Plan;
import com.ayush.chat.tenant.model.TenantLimits;

import java.math.BigDecimal;
import java.util.Set;

public record PlanView(String code, String name, String description, BigDecimal priceMonthly, BigDecimal priceYearly,
                       String currency, TenantLimits limits, Set<String> features, boolean isDefault, boolean active,
                       int sortOrder, long tenantCount) {

    public static PlanView of(Plan p, long tenantCount) {
        return new PlanView(p.getCode(), p.getName(), p.getDescription(), p.getPriceMonthly(), p.getPriceYearly(),
                p.getCurrency(), p.limitsOrNone(), p.getFeatureIds(), p.isDefaultPlan(), p.isActive(), p.getSortOrder(),
                tenantCount);
    }
}
