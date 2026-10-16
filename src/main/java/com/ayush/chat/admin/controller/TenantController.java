package com.ayush.chat.admin.controller;

import com.ayush.chat.admin.dto.request.CreateTenantRequest;
import com.ayush.chat.admin.dto.request.FeatureOverridesRequest;
import com.ayush.chat.admin.dto.response.CreatedTenantResponse;
import com.ayush.chat.admin.dto.response.FeatureState;
import com.ayush.chat.admin.dto.response.TenantDetail;
import com.ayush.chat.admin.dto.response.TenantSummary;
import com.ayush.chat.admin.service.TenantAdminService;
import com.ayush.chat.admin.service.TenantQueryService;
import com.ayush.chat.core.exception.DomainException;
import com.ayush.chat.tenant.dto.DailyUsage;
import com.ayush.chat.tenant.dto.UpdateTenantRequest;
import com.ayush.chat.tenant.entity.Tenant;
import com.ayush.chat.tenant.service.TenantService;
import com.ayush.chat.tenant.service.UsageMeter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Tenant lifecycle, features and usage. */
@RestController
@RequestMapping("/api/admin/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantAdminService admin;
    private final TenantQueryService queries;
    private final TenantService tenants;
    private final UsageMeter usage;

    @GetMapping
    public List<TenantSummary> list(@RequestParam(required = false) String q, @RequestParam(required = false) String status) {
        return queries.list(q, parseStatus(status));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedTenantResponse create(@RequestBody CreateTenantRequest request) {
        return admin.create(request);
    }

    @GetMapping("/{slug}")
    public TenantDetail get(@PathVariable String slug) {
        return queries.detail(slug);
    }

    @PutMapping("/{slug}")
    public TenantDetail update(@PathVariable String slug, @RequestBody UpdateTenantRequest update) {
        return admin.update(slug, update);
    }

    @PostMapping("/{slug}/suspend")
    public TenantDetail suspend(@PathVariable String slug) {
        return admin.suspend(slug);
    }

    @PostMapping("/{slug}/activate")
    public TenantDetail activate(@PathVariable String slug) {
        return admin.activate(slug);
    }

    @DeleteMapping("/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String slug) {
        admin.delete(slug);
    }

    @PutMapping("/{slug}/features")
    public List<FeatureState> features(@PathVariable String slug, @RequestBody FeatureOverridesRequest request) {
        return admin.setFeatures(slug, request.overrides());
    }

    @GetMapping("/{slug}/usage")
    public List<DailyUsage> usage(@PathVariable String slug, @RequestParam(defaultValue = "30") int days) {
        tenants.get(slug);
        return usage.history(slug, days);
    }

    private static Tenant.Status parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return Arrays.stream(Tenant.Status.values()).filter(s -> s.name().equals(status.trim().toUpperCase(Locale.ROOT)))
                .findFirst().orElseThrow(() -> DomainException.badRequest("Unknown status '" + status + "'"));
    }
}
