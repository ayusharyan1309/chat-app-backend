package com.ayush.chat.admin.controller;

import com.ayush.chat.saas.config.DatabaseType;
import com.ayush.chat.saas.feature.FeatureFlagService;
import com.ayush.chat.saas.feature.FeatureModule;
import com.ayush.chat.saas.tenant.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin controller for SaaS tenant management.
 * Provides endpoints for super-admin operations.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/tenants")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class TenantAdminController {

    private final TenantService tenantService;
    private final TenantRepository tenantRepository;
    private final FeatureFlagService featureFlagService;

    /**
     * Creates a new tenant.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createTenant(@RequestBody CreateTenantRequest request) {
        try {
            Tenant tenant = tenantService.createTenant(request);
            
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "Tenant created successfully");
            response.put("tenant", tenantToMap(tenant));
            response.put("defaultFeatures", FeatureModule.getDefaultModules().stream()
                    .map(FeatureModule::getId)
                    .collect(Collectors.toList()));
            
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            log.error("Error creating tenant", e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("message", "Failed to create tenant: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Gets all tenants.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllTenants(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String planType) {
        
        List<Tenant> tenants;
        if (status != null) {
            tenants = tenantRepository.findByStatus(status);
        } else if (planType != null) {
            tenants = tenantService.getTenantsByPlan(planType);
        } else {
            tenants = tenantService.getAllActiveTenants();
        }
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("total", tenants.size());
        response.put("tenants", tenants.stream()
                .map(this::tenantToMap)
                .collect(Collectors.toList()));
        
        return ResponseEntity.ok(response);
    }

    /**
     * Gets a specific tenant by ID.
     */
    @GetMapping("/{tenantId}")
    public ResponseEntity<Map<String, Object>> getTenant(@PathVariable Long tenantId) {
        try {
            Tenant tenant = tenantService.getTenantById(tenantId);
            Set<FeatureModule> features = featureFlagService.getEnabledFeatures(
                    tenant.getTenantIdentifier());
            
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("tenant", tenantToMap(tenant));
            response.put("enabledFeatures", features.stream()
                    .map(FeatureModule::getId)
                    .collect(Collectors.toList()));
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("message", "Tenant not found");
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Updates a tenant.
     */
    @PutMapping("/{tenantId}")
    public ResponseEntity<Map<String, Object>> updateTenant(
            @PathVariable Long tenantId,
            @RequestBody UpdateTenantRequest request) {
        try {
            Tenant tenant = tenantService.updateTenant(tenantId, request);
            
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "Tenant updated successfully");
            response.put("tenant", tenantToMap(tenant));
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("message", "Failed to update tenant: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Suspends a tenant.
     */
    @PostMapping("/{tenantId}/suspend")
    public ResponseEntity<Map<String, Object>> suspendTenant(@PathVariable Long tenantId) {
        try {
            tenantService.suspendTenant(tenantId);
            
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "Tenant suspended successfully");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("message", "Failed to suspend tenant: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Activates a suspended tenant.
     */
    @PostMapping("/{tenantId}/activate")
    public ResponseEntity<Map<String, Object>> activateTenant(@PathVariable Long tenantId) {
        try {
            tenantService.activateTenant(tenantId);
            
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "Tenant activated successfully");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("message", "Failed to activate tenant: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Deletes a tenant (soft delete).
     */
    @DeleteMapping("/{tenantId}")
    public ResponseEntity<Map<String, Object>> deleteTenant(@PathVariable Long tenantId) {
        try {
            tenantService.deleteTenant(tenantId);
            
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "Tenant deleted successfully");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("message", "Failed to delete tenant: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Updates tenant features.
     */
    @PutMapping("/{tenantId}/features")
    public ResponseEntity<Map<String, Object>> updateTenantFeatures(
            @PathVariable Long tenantId,
            @RequestBody Map<String, List<String>> request) {
        try {
            List<String> featureIds = request.get("features");
            tenantService.updateTenantFeatures(tenantId, new HashSet<>(featureIds));
            
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "Tenant features updated successfully");
            response.put("enabledFeatures", featureIds);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("message", "Failed to update features: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Updates tenant database configuration.
     */
    @PutMapping("/{tenantId}/database")
    public ResponseEntity<Map<String, Object>> updateTenantDatabase(
            @PathVariable Long tenantId,
            @RequestBody Map<String, Object> dbConfig) {
        try {
            String dbHost = (String) dbConfig.get("dbHost");
            int dbPort = (int) dbConfig.get("dbPort");
            String dbName = (String) dbConfig.get("dbName");
            String dbUsername = (String) dbConfig.get("dbUsername");
            String dbPassword = (String) dbConfig.get("dbPassword");
            
            tenantService.updateTenantDatabase(tenantId, dbHost, dbPort, dbName, dbUsername, dbPassword);
            
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "Database configuration updated successfully");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("message", "Failed to update database: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Gets tenant statistics.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getTenantStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalActiveTenants", tenantService.getAllActiveTenants().size());
        stats.put("expiredTrials", tenantService.getExpiredTrialTenants().size());
        
        // Count by plan type
        Map<String, Long> planCounts = new LinkedHashMap<>();
        for (String plan : Arrays.asList("FREE", "BASIC", "PRO", "ENTERPRISE")) {
            planCounts.put(plan, (long) tenantService.getTenantsByPlan(plan).size());
        }
        stats.put("tenantsByPlan", planCounts);
        
        // Count by status
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (String status : Arrays.asList("ACTIVE", "SUSPENDED", "TRIAL")) {
            statusCounts.put(status, (long) tenantRepository.findByStatus(status).size());
        }
        stats.put("tenantsByStatus", statusCounts);
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("stats", stats);
        
        return ResponseEntity.ok(response);
    }

    /**
     * Gets available database types.
     */
    @GetMapping("/database-types")
    public ResponseEntity<Map<String, Object>> getDatabaseTypes() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("databaseTypes", Arrays.stream(DatabaseType.values())
                .map(type -> {
                    Map<String, String> info = new LinkedHashMap<>();
                    info.put("id", type.name());
                    info.put("name", type.getName());
                    info.put("driverClass", type.getDriverClassName());
                    return info;
                })
                .collect(Collectors.toList()));
        
        return ResponseEntity.ok(response);
    }

    /**
     * Gets available feature modules.
     */
    @GetMapping("/features")
    public ResponseEntity<Map<String, Object>> getAvailableFeatures() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("features", Arrays.stream(FeatureModule.values())
                .map(module -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("id", module.getId());
                    info.put("description", module.getDescription());
                    info.put("includedByDefault", module.isIncludedByDefault());
                    return info;
                })
                .collect(Collectors.toList()));
        
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> tenantToMap(Tenant tenant) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", tenant.getId());
        map.put("tenantIdentifier", tenant.getTenantIdentifier());
        map.put("tenantName", tenant.getTenantName());
        map.put("displayName", tenant.getDisplayName());
        map.put("domain", tenant.getDomain());
        map.put("contactEmail", tenant.getContactEmail());
        map.put("databaseType", tenant.getDatabaseType() != null ? 
                tenant.getDatabaseType().getName() : null);
        map.put("maxUsers", tenant.getMaxUsers());
        map.put("maxStorageGb", tenant.getMaxStorageGb());
        map.put("planType", tenant.getPlanType());
        map.put("status", tenant.getStatus());
        map.put("isActive", tenant.getIsActive());
        map.put("createdAt", tenant.getCreatedAt());
        map.put("updatedAt", tenant.getUpdatedAt());
        return map;
    }
}
