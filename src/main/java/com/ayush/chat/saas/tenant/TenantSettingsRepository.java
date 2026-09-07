package com.ayush.chat.saas.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for TenantSettings entity operations.
 */
@Repository
public interface TenantSettingsRepository extends JpaRepository<TenantSettings, Long> {

    List<TenantSettings> findByTenant(Tenant tenant);

    Optional<TenantSettings> findByTenantAndSettingKey(Tenant tenant, String settingKey);

    Optional<TenantSettings> findByTenant_IdAndSettingKey(Long tenantId, String settingKey);

    void deleteByTenant(Tenant tenant);
}
