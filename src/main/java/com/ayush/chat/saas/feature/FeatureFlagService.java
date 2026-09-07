package com.ayush.chat.saas.feature;

import com.ayush.chat.saas.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing feature flags per tenant.
 * Supports runtime toggling of features without restart.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureFlagService {

    private final Map<String, Set<FeatureModule>> tenantFeatures = new ConcurrentHashMap<>();

    /**
     * Initializes default features for a new tenant.
     */
    public void initializeTenantFeatures(String tenantId) {
        Set<FeatureModule> defaultFeatures = new HashSet<>(FeatureModule.getDefaultModules());
        tenantFeatures.put(tenantId, defaultFeatures);
        log.info("Initialized default features for tenant: {}", tenantId);
    }

    /**
     * Checks if a feature is enabled for the current tenant.
     */
    public boolean isFeatureEnabled(FeatureModule module) {
        String tenantId = String.valueOf(TenantContext.getCurrentTenantId());
        return isFeatureEnabled(tenantId, module);
    }

    /**
     * Checks if a feature is enabled for a specific tenant.
     */
    public boolean isFeatureEnabled(String tenantId, FeatureModule module) {
        Set<FeatureModule> features = tenantFeatures.get(tenantId);
        if (features == null) {
            log.warn("No features found for tenant: {}. Initializing defaults.", tenantId);
            initializeTenantFeatures(tenantId);
            features = tenantFeatures.get(tenantId);
        }
        return features.contains(module);
    }

    /**
     * Enables a feature for a tenant.
     */
    public void enableFeature(String tenantId, FeatureModule module) {
        tenantFeatures.computeIfAbsent(tenantId, k -> new HashSet<>()).add(module);
        log.info("Enabled feature {} for tenant {}", module.getId(), tenantId);
    }

    /**
     * Disables a feature for a tenant.
     */
    public void disableFeature(String tenantId, FeatureModule module) {
        Set<FeatureModule> features = tenantFeatures.get(tenantId);
        if (features != null) {
            features.remove(module);
            log.info("Disabled feature {} for tenant {}", module.getId(), tenantId);
        }
    }

    /**
     * Gets all enabled features for a tenant.
     */
    public Set<FeatureModule> getEnabledFeatures(String tenantId) {
        return tenantFeatures.getOrDefault(tenantId, new HashSet<>());
    }

    /**
     * Bulk update features for a tenant.
     */
    public void updateFeatures(String tenantId, Set<String> featureIds) {
        Set<FeatureModule> features = new HashSet<>();
        for (String featureId : featureIds) {
            try {
                features.add(FeatureModule.fromId(featureId));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown feature module: {}", featureId);
            }
        }
        tenantFeatures.put(tenantId, features);
        log.info("Updated features for tenant: {}", tenantId);
    }

    /**
     * Removes all feature configurations for a tenant (useful for tenant deletion).
     */
    public void removeTenantFeatures(String tenantId) {
        tenantFeatures.remove(tenantId);
        log.info("Removed features for tenant: {}", tenantId);
    }
}
