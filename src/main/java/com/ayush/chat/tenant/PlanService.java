package com.ayush.chat.tenant;

import com.ayush.chat.audit.AuditAction;
import com.ayush.chat.audit.AuditService;
import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.core.util.Json;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Subscription plans: the limits and features tenants inherit. */
@Service
@RequiredArgsConstructor
public class PlanService {

    private static final Pattern CODE = Pattern.compile("^[a-z0-9][a-z0-9-]{1,49}$");
    private static final String DEFAULT_CURRENCY = "USD";

    private final PlanRepository plans;
    private final TenantRepository tenants;
    private final ApplicationEventPublisher events;
    private final AuditService audit;

    public record PlanInput(String code, String name, String description, BigDecimal priceMonthly, BigDecimal priceYearly,
                            String currency, TenantLimits limits, Set<String> features, Boolean isDefault, Boolean active,
                            Integer sortOrder) {
    }

    public record PlanView(String code, String name, String description, BigDecimal priceMonthly, BigDecimal priceYearly,
                           String currency, TenantLimits limits, Set<String> features, boolean isDefault, boolean active,
                           int sortOrder, long tenantCount) {

        static PlanView of(Plan p, long tenantCount) {
            return new PlanView(p.getCode(), p.getName(), p.getDescription(), p.getPriceMonthly(), p.getPriceYearly(),
                    p.getCurrency(), p.limitsOrNone(), p.getFeatureIds(), p.isDefaultPlan(), p.isActive(), p.getSortOrder(),
                    tenantCount);
        }
    }

    @Transactional(readOnly = true)
    public List<PlanView> list() {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : tenants.countByPlan()) {
            counts.put((String) row[0], (Long) row[1]);
        }
        return plans.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(p -> PlanView.of(p, counts.getOrDefault(p.getCode(), 0L))).toList();
    }

    @Transactional
    public PlanView create(PlanInput input) {
        if (input.code() == null || !CODE.matcher(input.code()).matches()) {
            throw DomainException.badRequest("code must be 2-50 chars: lowercase letters, digits and dashes");
        }
        if (plans.findByCode(input.code()).isPresent()) {
            throw DomainException.conflict("Plan '" + input.code() + "' already exists");
        }
        Plan plan = new Plan();
        plan.setCode(input.code());
        apply(plan, input);
        plans.save(plan);
        audit.record(AuditAction.PLAN_CREATED, plan.getCode(), null, Json.write(input));
        return PlanView.of(plan, 0);
    }

    /** Changes reach every tenant on the plan (all nodes drop their cached snapshots after commit). */
    @Transactional
    public PlanView update(String code, PlanInput input) {
        Plan plan = get(code);
        apply(plan, input);
        events.publishEvent(new PlanChangedEvent(code));
        audit.record(AuditAction.PLAN_UPDATED, code, null, Json.write(input));
        return PlanView.of(plan, tenants.countByPlanCode(code));
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
        audit.record(AuditAction.PLAN_DELETED, code, null, null);
    }

    private Plan get(String code) {
        return plans.findByCode(code).orElseThrow(() -> DomainException.notFound("Plan '" + code + "'"));
    }

    private void apply(Plan plan, PlanInput input) {
        if (input.name() == null || input.name().isBlank()) {
            throw DomainException.badRequest("name is required");
        }
        plan.setName(input.name().trim());
        plan.setDescription(input.description());
        plan.setPriceMonthly(input.priceMonthly() != null ? input.priceMonthly() : BigDecimal.ZERO);
        plan.setPriceYearly(input.priceYearly() != null ? input.priceYearly() : BigDecimal.ZERO);
        plan.setCurrency(input.currency() != null && !input.currency().isBlank()
                ? input.currency().toUpperCase(Locale.ROOT) : DEFAULT_CURRENCY);
        plan.setLimits(input.limits() != null ? input.limits() : TenantLimits.NONE);
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
            plans.findFirstByDefaultPlanTrue().ifPresent(previous -> previous.setDefaultPlan(false));
            plan.setDefaultPlan(true);
        }
    }
}
