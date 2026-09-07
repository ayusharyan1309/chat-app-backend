package com.ayush.chat.saas.tenant;

import com.ayush.chat.saas.config.DatabaseType;
import com.ayush.chat.saas.config.DynamicDataSourceConfig;
import com.ayush.chat.saas.feature.FeatureFlagService;
import com.ayush.chat.saas.feature.FeatureModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.*;

/**
 * Service for managing tenant lifecycle and configuration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantRepository tenantRepository;
    private final TenantSettingsRepository tenantSettingsRepository;
    private final TenantFeatureRepository tenantFeatureRepository;
    private final DynamicDataSourceConfig dataSourceConfig;
    private final FeatureFlagService featureFlagService;

    /**
     * Creates a new tenant with default settings and features.
     */
    @Transactional
    public Tenant createTenant(CreateTenantRequest request) {
        // Generate unique tenant identifier
        String tenantIdentifier = generateTenantIdentifier(request.getTenantName());
        
        // Create tenant entity
        Tenant tenant = Tenant.builder()
                .tenantIdentifier(tenantIdentifier)
                .tenantName(request.getTenantName())
                .displayName(request.getDisplayName())
                .domain(request.getDomain())
                .contactEmail(request.getContactEmail())
                .contactPhone(request.getContactPhone())
                .databaseType(request.getDatabaseType() != null ? 
                        request.getDatabaseType() : DatabaseType.H2)
                .maxUsers(request.getMaxUsers() != null ? request.getMaxUsers() : 100)
                .maxStorageGb(request.getMaxStorageGb() != null ? request.getMaxStorageGb() : 10)
                .maxMessagesPerDay(request.getMaxMessagesPerDay() != null ? 
                        request.getMaxMessagesPerDay() : 10000L)
                .planType(request.getPlanType() != null ? request.getPlanType() : "FREE")
                .status("ACTIVE")
                .isActive(true)
                .build();

        tenant = tenantRepository.save(tenant);
        log.info("Created tenant: {} with identifier: {}", tenant.getTenantName(), tenantIdentifier);

        // Initialize default features
        initializeTenantFeatures(tenant);

        // Initialize default settings
        initializeTenantSettings(tenant);

        // Create database for tenant if external database is not specified
        if (request.getExternalDatabaseUrl() == null) {
            createTenantDatabase(tenant);
        }

        return tenant;
    }

    /**
     * Gets a tenant by its identifier.
     */
    public Tenant getTenantByIdentifier(String tenantIdentifier) {
        return tenantRepository.findByTenantIdentifier(tenantIdentifier)
                .orElseThrow(() -> new RuntimeException("Tenant not found: " + tenantIdentifier));
    }

    /**
     * Gets a tenant by ID.
     */
    public Tenant getTenantById(Long tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new RuntimeException("Tenant not found with ID: " + tenantId));
    }

    /**
     * Updates tenant configuration.
     */
    @Transactional
    public Tenant updateTenant(Long tenantId, UpdateTenantRequest request) {
        Tenant tenant = getTenantById(tenantId);
        
        if (request.getDisplayName() != null) {
            tenant.setDisplayName(request.getDisplayName());
        }
        if (request.getLogoUrl() != null) {
            tenant.setLogoUrl(request.getLogoUrl());
        }
        if (request.getPrimaryColor() != null) {
            tenant.setPrimaryColor(request.getPrimaryColor());
        }
        if (request.getSecondaryColor() != null) {
            tenant.setSecondaryColor(request.getSecondaryColor());
        }
        if (request.getMaxUsers() != null) {
            tenant.setMaxUsers(request.getMaxUsers());
        }
        if (request.getMaxStorageGb() != null) {
            tenant.setMaxStorageGb(request.getMaxStorageGb());
        }
        if (request.getMaxMessagesPerDay() != null) {
            tenant.setMaxMessagesPerDay(request.getMaxMessagesPerDay());
        }
        if (request.getPlanType() != null) {
            tenant.setPlanType(request.getPlanType());
        }

        tenant = tenantRepository.save(tenant);
        log.info("Updated tenant: {}", tenant.getTenantIdentifier());
        return tenant;
    }

    /**
     * Suspends a tenant.
     */
    @Transactional
    public void suspendTenant(Long tenantId) {
        Tenant tenant = getTenantById(tenantId);
        tenant.setStatus("SUSPENDED");
        tenant.setSuspendedAt(new Timestamp(System.currentTimeMillis()));
        tenantRepository.save(tenant);
        log.info("Suspended tenant: {}", tenant.getTenantIdentifier());
    }

    /**
     * Activates a suspended tenant.
     */
    @Transactional
    public void activateTenant(Long tenantId) {
        Tenant tenant = getTenantById(tenantId);
        tenant.setStatus("ACTIVE");
        tenant.setSuspendedAt(null);
        tenantRepository.save(tenant);
        log.info("Activated tenant: {}", tenant.getTenantIdentifier());
    }

    /**
     * Soft deletes a tenant.
     */
    @Transactional
    public void deleteTenant(Long tenantId) {
        Tenant tenant = getTenantById(tenantId);
        tenant.setStatus("DELETED");
        tenant.setIsActive(false);
        tenantRepository.save(tenant);
        
        // Clean up tenant resources
        dataSourceConfig.removeTenantDataSource(tenant.getTenantIdentifier());
        featureFlagService.removeTenantFeatures(tenant.getTenantIdentifier());
        
        log.info("Soft deleted tenant: {}", tenant.getTenantIdentifier());
    }

    /**
     * Updates tenant database configuration.
     */
    @Transactional
    public void updateTenantDatabase(Long tenantId, String dbHost, int dbPort, 
                                      String dbName, String dbUsername, String dbPassword) {
        Tenant tenant = getTenantById(tenantId);
        tenant.setDbHost(dbHost);
        tenant.setDbPort(dbPort);
        tenant.setDbName(dbName);
        tenant.setDbUsername(dbUsername);
        tenant.setDbPassword(dbPassword);
        tenantRepository.save(tenant);
        
        // Refresh data source
        dataSourceConfig.refreshTenantDataSource(tenant.getTenantIdentifier());
        log.info("Updated database configuration for tenant: {}", tenant.getTenantIdentifier());
    }

    /**
     * Gets all active tenants.
     */
    public List<Tenant> getAllActiveTenants() {
        return tenantRepository.findByIsActive(true);
    }

    /**
     * Gets tenants by plan type.
     */
    public List<Tenant> getTenantsByPlan(String planType) {
        return tenantRepository.findByPlanType(planType);
    }

    /**
     * Gets expired trial tenants.
     */
    public List<Tenant> getExpiredTrialTenants() {
        return tenantRepository.findExpiredTrials();
    }

    /**
     * Sets a tenant setting.
     */
    @Transactional
    public void setTenantSetting(Long tenantId, String key, String value, String type) {
        Tenant tenant = getTenantById(tenantId);
        TenantSettings setting = tenantSettingsRepository
                .findByTenant_IdAndSettingKey(tenantId, key)
                .orElse(TenantSettings.builder()
                        .tenant(tenant)
                        .settingKey(key)
                        .build());
        
        setting.setSettingValue(value);
        setting.setSettingType(type);
        tenantSettingsRepository.save(setting);
    }

    /**
     * Gets a tenant setting.
     */
    public Optional<String> getTenantSetting(Long tenantId, String key) {
        return tenantSettingsRepository.findByTenant_IdAndSettingKey(tenantId, key)
                .map(TenantSettings::getSettingValue);
    }

    /**
     * Updates tenant features.
     */
    @Transactional
    public void updateTenantFeatures(Long tenantId, Set<String> featureIds) {
        Tenant tenant = getTenantById(tenantId);
        
        // Clear existing features
        tenantFeatureRepository.deleteByTenant(tenant);
        
        // Add new features
        for (String featureId : featureIds) {
            try {
                FeatureModule module = FeatureModule.fromId(featureId);
                TenantFeature tenantFeature = TenantFeature.builder()
                        .tenant(tenant)
                        .featureId(module)
                        .isEnabled(true)
                        .build();
                tenantFeatureRepository.save(tenantFeature);
            } catch (IllegalArgumentException e) {
                log.warn("Unknown feature module: {}", featureId);
            }
        }
        
        // Update in-memory cache
        featureFlagService.updateFeatures(tenant.getTenantIdentifier(), featureIds);
        log.info("Updated features for tenant: {}", tenant.getTenantIdentifier());
    }

    // Private helper methods

    private String generateTenantIdentifier(String tenantName) {
        String base = tenantName.toLowerCase()
                .replaceAll("[^a-z0-9]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        
        // Ensure uniqueness
        String identifier = base;
        int counter = 1;
        while (tenantRepository.existsByTenantIdentifier(identifier)) {
            identifier = base + "-" + counter;
            counter++;
        }
        
        return identifier;
    }

    private void initializeTenantFeatures(Tenant tenant) {
        List<FeatureModule> defaultFeatures = FeatureModule.getDefaultModules();
        for (FeatureModule module : defaultFeatures) {
            TenantFeature tenantFeature = TenantFeature.builder()
                    .tenant(tenant)
                    .featureId(module)
                    .isEnabled(true)
                    .build();
            tenantFeatureRepository.save(tenantFeature);
        }
        featureFlagService.initializeTenantFeatures(tenant.getTenantIdentifier());
    }

    private void initializeTenantSettings(Tenant tenant) {
        // Initialize default settings
        setTenantSetting(tenant.getId(), "chat.max_message_length", "5000", "NUMBER");
        setTenantSetting(tenant.getId(), "chat.allow_file_upload", "true", "BOOLEAN");
        setTenantSetting(tenant.getId(), "chat.max_file_size_mb", "25", "NUMBER");
        setTenantSetting(tenant.getId(), "chat.message_retention_days", "365", "NUMBER");
        setTenantSetting(tenant.getId(), "auth.allow_registration", "true", "BOOLEAN");
        setTenantSetting(tenant.getId(), "auth.session_timeout_hours", "24", "NUMBER");
    }

    private void createTenantDatabase(Tenant tenant) {
        log.info("Creating database for tenant: {}", tenant.getTenantIdentifier());
        // Database creation logic based on tenant's database type
        // This is handled by DynamicDataSourceConfig
    }
}
