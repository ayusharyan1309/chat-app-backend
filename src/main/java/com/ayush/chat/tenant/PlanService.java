package com.ayush.chat.tenant;

import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.tenant.TenantRepositories.PlanRepository;
import com.ayush.chat.tenant.TenantRepositories.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class PlanService {

    private static final Pattern CODE = Pattern.compile("^[a-z0-9][a-z0-9-]{1,49}$");

    private final PlanRepository plans;
    private final TenantRepository tenants;
    private final TenantDirectory directory;

    public record PlanInput(String code, String name, String description, BigDecimal priceMonthly, BigDecimal priceYearly,
                            String currency, TenantLimits limits, Set<String> features, Boolean isDefault, Boolean active,
                            Integer sortOrder) {
    }

    @Transactional(readOnly = true)
    public List<Plan> list() {
        return plans.findAllByOrderBySortOrderAscIdAsc();
    }

    @Transactional(readOnly = true)
    public Plan get(String code) {
        return plans.findByCode(code).orElseThrow(() -> DomainException.notFound("Plan '" + code + "'"));
    }

    public long tenantCount(String code) {
        return tenants.countByPlanCode(code);
    }

    @Transactional
    public Plan create(PlanInput input) {
        if (input.code() == null || !CODE.matcher(input.code()).matches()) {
            throw DomainException.badRequest("code must be 2-50 chars: lowercase letters, digits and dashes");
        }
        if (plans.findByCode(input.code()).isPresent()) {
            throw DomainException.conflict("Plan '" + input.code() + "' already exists");
        }
        Plan plan = new Plan();
        plan.setCode(input.code());
        apply(plan, input);
        return plans.save(plan);
    }

    @Transactional
    public Plan update(String code, PlanInput input) {
        Plan plan = get(code);
        apply(plan, input);
        plans.save(plan);
        directory.invalidateAll();
        return plan;
    }

    @Transactional
    public void delete(String code) {
        Plan plan = get(code);
        if (plan.isDefaultPlan()) {
            throw DomainException.conflict("The default plan cannot be deleted");
        }
        long inUse = tenants.countByPlanCode(code);
        if (inUse > 0) {
            throw DomainException.conflict("Plan is used by " + inUse + " tenant(s)");
        }
        plans.delete(plan);
    }

    private void apply(Plan plan, PlanInput input) {
        if (input.name() == null || input.name().isBlank()) {
            throw DomainException.badRequest("name is required");
        }
        plan.setName(input.name().trim());
        plan.setDescription(input.description());
        plan.setPriceMonthly(input.priceMonthly() != null ? input.priceMonthly() : BigDecimal.ZERO);
        plan.setPriceYearly(input.priceYearly() != null ? input.priceYearly() : BigDecimal.ZERO);
        plan.setCurrency(input.currency() != null && !input.currency().isBlank() ? input.currency().toUpperCase() : "USD");
        TenantLimits l = input.limits() != null ? input.limits() : TenantLimits.NONE;
        plan.setMaxUsers(l.maxUsers());
        plan.setMaxMessagesPerDay(l.maxMessagesPerDay());
        plan.setMaxConnections(l.maxConnections());
        plan.setMaxStorageMb(l.maxStorageMb());
        plan.setMaxGroupSize(l.maxGroupSize());
        plan.setMessagesPerSecond(l.messagesPerSecond());
        Set<String> features = new LinkedHashSet<>();
        for (String id : input.features() == null ? Set.<String>of() : input.features()) {
            Feature f = Feature.fromId(id).orElseThrow(() -> DomainException.badRequest("Unknown feature: " + id));
            if (f.isToggleable()) {
                features.add(f.getId());
            }
        }
        plan.setFeatureIds(features);
        if (input.active() != null) {
            plan.setActive(input.active());
        }
        if (input.sortOrder() != null) {
            plan.setSortOrder(input.sortOrder());
        }
        if (Boolean.TRUE.equals(input.isDefault()) && !plan.isDefaultPlan()) {
            plans.findFirstByDefaultPlanTrue().ifPresent(previous -> {
                previous.setDefaultPlan(false);
                plans.save(previous);
            });
            plan.setDefaultPlan(true);
        }
    }
}
