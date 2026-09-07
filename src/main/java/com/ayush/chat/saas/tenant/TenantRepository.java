package com.ayush.chat.saas.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Tenant entity operations.
 */
@Repository
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findByTenantIdentifier(String tenantIdentifier);

    Optional<Tenant> findByDomain(String domain);

    Optional<Tenant> findByContactEmail(String contactEmail);

    List<Tenant> findByIsActive(Boolean isActive);

    @Query("SELECT t FROM Tenant t WHERE t.status = :status")
    List<Tenant> findByStatus(@Param("status") String status);

    @Query("SELECT t FROM Tenant t WHERE t.planType = :planType")
    List<Tenant> findByPlanType(@Param("planType") String planType);

    @Query("SELECT t FROM Tenant t WHERE t.trialEndsAt < CURRENT_TIMESTAMP AND t.status = 'TRIAL'")
    List<Tenant> findExpiredTrials();

    boolean existsByTenantIdentifier(String tenantIdentifier);

    boolean existsByDomain(String domain);
}
