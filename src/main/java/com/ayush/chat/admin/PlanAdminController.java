package com.ayush.chat.admin;

import com.ayush.chat.audit.AuditService;
import com.ayush.chat.core.util.Json;
import com.ayush.chat.tenant.Plan;
import com.ayush.chat.tenant.PlanService;
import com.ayush.chat.tenant.TenantLimits;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/plans")
@RequiredArgsConstructor
public class PlanAdminController {

    private final PlanService plans;
    private final AuditService audit;

    public record PlanView(String code, String name, String description, BigDecimal priceMonthly, BigDecimal priceYearly,
                           String currency, TenantLimits limits, Set<String> features, boolean isDefault, boolean active,
                           int sortOrder, long tenantCount) {
    }

    @GetMapping
    public List<PlanView> list() {
        return plans.list().stream().map(this::view).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlanView create(@RequestBody PlanService.PlanInput input) {
        Plan plan = plans.create(input);
        audit.record("plan.created", "plan", plan.getCode(), null, Json.write(input));
        return view(plan);
    }

    @PutMapping("/{code}")
    public PlanView update(@PathVariable String code, @RequestBody PlanService.PlanInput input) {
        Plan plan = plans.update(code, input);
        audit.record("plan.updated", "plan", code, null, Json.write(input));
        return view(plan);
    }

    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String code) {
        plans.delete(code);
        audit.record("plan.deleted", "plan", code, null, null);
    }

    private PlanView view(Plan p) {
        return new PlanView(p.getCode(), p.getName(), p.getDescription(), p.getPriceMonthly(), p.getPriceYearly(),
                p.getCurrency(), p.limits(), p.featureIds(), p.isDefaultPlan(), p.isActive(), p.getSortOrder(),
                plans.tenantCount(p.getCode()));
    }
}
