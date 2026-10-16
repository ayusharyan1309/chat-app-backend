package com.ayush.chat.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
@Setter
@Entity
@Table(name = "platform_plans")
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "price_monthly")
    private BigDecimal priceMonthly;

    @Column(name = "price_yearly")
    private BigDecimal priceYearly;

    private String currency;

    @Column(name = "max_users")
    private Long maxUsers;

    @Column(name = "max_messages_per_day")
    private Long maxMessagesPerDay;

    @Column(name = "max_connections")
    private Long maxConnections;

    @Column(name = "max_storage_mb")
    private Long maxStorageMb;

    @Column(name = "max_group_size")
    private Integer maxGroupSize;

    @Column(name = "messages_per_second")
    private Integer messagesPerSecond;

    /** Comma-separated feature ids. */
    private String features;

    @Column(name = "is_default")
    private boolean defaultPlan;

    @Column(name = "is_active")
    private boolean active = true;

    @Column(name = "sort_order")
    private int sortOrder;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Set<String> featureIds() {
        if (features == null || features.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(features.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public void setFeatureIds(Set<String> ids) {
        this.features = String.join(",", ids);
    }

    public TenantLimits limits() {
        return new TenantLimits(maxUsers, maxMessagesPerDay, maxConnections, maxStorageMb, maxGroupSize, messagesPerSecond);
    }
}
