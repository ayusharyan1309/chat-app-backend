package com.ayush.chat.saas.subscription;

import com.ayush.chat.saas.tenant.Tenant;
import com.ayush.chat.saas.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;

/**
 * Service for managing tenant subscriptions and billing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final TenantSubscriptionRepository subscriptionRepository;
    private final SubscriptionPlanRepository planRepository;
    private final TenantRepository tenantRepository;

    /**
     * Creates a new subscription for a tenant.
     */
    @Transactional
    public TenantSubscription createSubscription(Tenant tenant, String planName, 
                                                  String billingCycle) {
        SubscriptionPlan plan = planRepository.findByPlanName(planName)
                .orElseThrow(() -> new RuntimeException("Plan not found: " + planName));

        TenantSubscription subscription = TenantSubscription.builder()
                .tenant(tenant)
                .plan(plan)
                .status(SubscriptionStatus.ACTIVE)
                .billingCycle(billingCycle)
                .amount(billingCycle.equals("YEARLY") ? plan.getPriceYearly() : plan.getPriceMonthly())
                .currency(plan.getCurrency())
                .startDate(new Timestamp(System.currentTimeMillis()))
                .nextBillingDate(calculateNextBillingDate(billingCycle))
                .build();

        subscription = subscriptionRepository.save(subscription);
        log.info("Created subscription for tenant: {} with plan: {}", 
                tenant.getTenantIdentifier(), planName);

        // Update tenant plan
        tenant.setPlanType(planName);
        tenant.setMaxUsers(plan.getMaxUsers());
        tenant.setMaxStorageGb(plan.getMaxStorageGb());
        tenant.setMaxMessagesPerDay(plan.getMaxMessagesPerDay());
        tenantRepository.save(tenant);

        return subscription;
    }

    /**
     * Creates a trial subscription.
     */
    @Transactional
    public TenantSubscription createTrialSubscription(Tenant tenant, int trialDays) {
        SubscriptionPlan trialPlan = planRepository.findByIsTrialPlan(true)
                .orElse(null);

        if (trialPlan == null) {
            // Create default trial plan if not exists
            trialPlan = createDefaultTrialPlan();
        }

        Timestamp trialEndDate = new Timestamp(System.currentTimeMillis() + 
                (trialDays * 24L * 60 * 60 * 1000));

        TenantSubscription subscription = TenantSubscription.builder()
                .tenant(tenant)
                .plan(trialPlan)
                .status(SubscriptionStatus.TRIAL)
                .isTrial(true)
                .trialEndsAt(trialEndDate)
                .amount(java.math.BigDecimal.ZERO)
                .currency("USD")
                .startDate(new Timestamp(System.currentTimeMillis()))
                .build();

        subscription = subscriptionRepository.save(subscription);
        log.info("Created trial subscription for tenant: {} ending: {}", 
                tenant.getTenantIdentifier(), trialEndDate);

        tenant.setTrialEndsAt(trialEndDate);
        tenant.setStatus("TRIAL");
        tenantRepository.save(tenant);

        return subscription;
    }

    /**
     * Upgrades a tenant's subscription.
     */
    @Transactional
    public TenantSubscription upgradeSubscription(Long tenantId, String newPlanName, 
                                                    String billingCycle) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new RuntimeException("Tenant not found"));

        // Cancel existing subscription
        TenantSubscription existingSub = subscriptionRepository.findByTenant(tenant)
                .orElse(null);
        if (existingSub != null) {
            existingSub.setStatus(SubscriptionStatus.CANCELLED);
            existingSub.setCancelledAt(new Timestamp(System.currentTimeMillis()));
            subscriptionRepository.save(existingSub);
        }

        // Create new subscription
        return createSubscription(tenant, newPlanName, billingCycle);
    }

    /**
     * Cancels a tenant's subscription.
     */
    @Transactional
    public void cancelSubscription(Long tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new RuntimeException("Tenant not found"));

        TenantSubscription subscription = subscriptionRepository.findByTenant(tenant)
                .orElseThrow(() -> new RuntimeException("No active subscription found"));

        subscription.setStatus(SubscriptionStatus.CANCELLED);
        subscription.setCancelledAt(new Timestamp(System.currentTimeMillis()));
        subscriptionRepository.save(subscription);

        // Downgrade tenant to free plan
        tenant.setPlanType("FREE");
        tenant.setStatus("ACTIVE");
        tenantRepository.save(tenant);

        log.info("Cancelled subscription for tenant: {}", tenant.getTenantIdentifier());
    }

    /**
     * Gets current subscription for a tenant.
     */
    public TenantSubscription getCurrentSubscription(Tenant tenant) {
        return subscriptionRepository.findByTenant(tenant).orElse(null);
    }

    /**
     * Checks and updates expired subscriptions.
     */
    @Transactional
    public void processExpiredSubscriptions() {
        List<TenantSubscription> expired = subscriptionRepository.findExpiredSubscriptions();
        for (TenantSubscription sub : expired) {
            sub.setStatus(SubscriptionStatus.EXPIRED);
            subscriptionRepository.save(sub);
            
            // Downgrade tenant
            Tenant tenant = sub.getTenant();
            tenant.setPlanType("FREE");
            tenantRepository.save(tenant);
            
            log.info("Expired subscription for tenant: {}", tenant.getTenantIdentifier());
        }
    }

    /**
     * Checks and updates expired trials.
     */
    @Transactional
    public void processExpiredTrials() {
        List<TenantSubscription> expiredTrials = subscriptionRepository.findExpiredTrials();
        for (TenantSubscription sub : expiredTrials) {
            sub.setStatus(SubscriptionStatus.EXPIRED);
            subscriptionRepository.save(sub);
            
            // Deactivate tenant
            Tenant tenant = sub.getTenant();
            tenant.setStatus("TRIAL_EXPIRED");
            tenant.setIsActive(false);
            tenantRepository.save(tenant);
            
            log.info("Expired trial for tenant: {}", tenant.getTenantIdentifier());
        }
    }

    private Timestamp calculateNextBillingDate(String billingCycle) {
        long interval = billingCycle.equals("YEARLY") ? 365L : 30L;
        return new Timestamp(System.currentTimeMillis() + (interval * 24L * 60 * 60 * 1000));
    }

    private SubscriptionPlan createDefaultTrialPlan() {
        SubscriptionPlan plan = SubscriptionPlan.builder()
                .planName("trial")
                .displayName("Free Trial")
                .description("14-day free trial with all features")
                .priceMonthly(java.math.BigDecimal.ZERO)
                .priceYearly(java.math.BigDecimal.ZERO)
                .maxUsers(10)
                .maxStorageGb(1)
                .maxMessagesPerDay(1000L)
                .isTrialPlan(true)
                .trialDurationDays(14)
                .isActive(true)
                .build();
        return planRepository.save(plan);
    }
}
