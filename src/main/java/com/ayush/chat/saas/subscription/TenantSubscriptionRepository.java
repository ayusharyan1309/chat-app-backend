package com.ayush.chat.saas.subscription;

import com.ayush.chat.saas.tenant.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantSubscriptionRepository extends JpaRepository<TenantSubscription, Long> {

    Optional<TenantSubscription> findByTenant(Tenant tenant);

    Optional<TenantSubscription> findByTenantAndStatus(Tenant tenant, SubscriptionStatus status);

    List<TenantSubscription> findByStatus(SubscriptionStatus status);

    @Query("SELECT ts FROM TenantSubscription ts WHERE ts.endDate < CURRENT_TIMESTAMP AND ts.status = 'ACTIVE'")
    List<TenantSubscription> findExpiredSubscriptions();

    @Query("SELECT ts FROM TenantSubscription ts WHERE ts.isTrial = true AND ts.trialEndsAt < CURRENT_TIMESTAMP")
    List<TenantSubscription> findExpiredTrials();
}
