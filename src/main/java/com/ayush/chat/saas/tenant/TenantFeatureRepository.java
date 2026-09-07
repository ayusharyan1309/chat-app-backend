package com.ayush.chat.saas.tenant;

import com.ayush.chat.saas.feature.FeatureModule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for TenantFeature entity operations.
 */
@Repository
public interface TenantFeatureRepository extends JpaRepository<TenantFeature, Long> {

    List<TenantFeature> findByTenant(Tenant tenant);

    List<TenantFeature> findByTenantAndIsEnabled(Tenant tenant, Boolean isEnabled);

    Optional<TenantFeature> findByTenantAndFeatureId(Tenant tenant, FeatureModule featureId);

    void deleteByTenant(Tenant tenant);
}
