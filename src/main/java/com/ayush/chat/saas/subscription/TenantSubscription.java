package com.ayush.chat.saas.subscription;

import com.ayush.chat.saas.tenant.Tenant;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * Tracks the current subscription for each tenant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "saas_tenant_subscriptions")
public class TenantSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private SubscriptionPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    @Builder.Default
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    @Column(name = "billing_cycle")
    @Builder.Default
    private String billingCycle = "MONTHLY"; // MONTHLY, YEARLY

    @Column(name = "amount", precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency")
    @Builder.Default
    private String currency = "USD";

    // Dates
    @Column(name = "start_date", nullable = false)
    private Timestamp startDate;

    @Column(name = "end_date")
    private Timestamp endDate;

    @Column(name = "next_billing_date")
    private Timestamp nextBillingDate;

    @Column(name = "cancelled_at")
    private Timestamp cancelledAt;

    // Trial info
    @Column(name = "is_trial")
    @Builder.Default
    private Boolean isTrial = false;

    @Column(name = "trial_ends_at")
    private Timestamp trialEndsAt;

    // Usage tracking
    @Column(name = "current_users")
    @Builder.Default
    private Integer currentUsers = 0;

    @Column(name = "current_storage_gb")
    @Builder.Default
    private BigDecimal currentStorageGb = BigDecimal.ZERO;

    @Column(name = "messages_today")
    @Builder.Default
    private Long messagesToday = 0L;

    // Timestamps
    @Column(name = "created_at", updatable = false)
    private Timestamp createdAt;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = new Timestamp(System.currentTimeMillis());
        updatedAt = new Timestamp(System.currentTimeMillis());
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = new Timestamp(System.currentTimeMillis());
    }

    /**
     * Check if subscription is currently active.
     */
    public boolean isActive() {
        return SubscriptionStatus.ACTIVE.equals(status);
    }

    /**
     * Check if trial has expired.
     */
    public boolean isTrialExpired() {
        if (!isTrial || trialEndsAt == null) return false;
        return new Timestamp(System.currentTimeMillis()).after(trialEndsAt);
    }
}
