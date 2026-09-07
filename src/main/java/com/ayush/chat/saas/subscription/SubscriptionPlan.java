package com.ayush.chat.saas.subscription;

import com.ayush.chat.saas.feature.FeatureModule;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Set;

/**
 * Subscription plan definition for the SaaS platform.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "saas_subscription_plans")
public class SubscriptionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_name", unique = true, nullable = false)
    private String planName;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "price_monthly", precision = 10, scale = 2)
    private BigDecimal priceMonthly;

    @Column(name = "price_yearly", precision = 10, scale = 2)
    private BigDecimal priceYearly;

    @Column(name = "currency")
    @Builder.Default
    private String currency = "USD";

    // Limits
    @Column(name = "max_users")
    @Builder.Default
    private Integer maxUsers = 100;

    @Column(name = "max_storage_gb")
    @Builder.Default
    private Integer maxStorageGb = 10;

    @Column(name = "max_messages_per_day")
    @Builder.Default
    private Long maxMessagesPerDay = 10000L;

    @Column(name = "max_concurrent_connections")
    @Builder.Default
    private Integer maxConcurrentConnections = 50;

    // Features included in this plan
    @ElementCollection(targetClass = FeatureModule.class)
    @CollectionTable(name = "saas_plan_features", joinColumns = @JoinColumn(name = "plan_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "feature_id")
    private Set<FeatureModule> includedFeatures;

    // Status
    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "is_trial_plan")
    @Builder.Default
    private Boolean isTrialPlan = false;

    @Column(name = "trial_duration_days")
    @Builder.Default
    private Integer trialDurationDays = 14;

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
}
