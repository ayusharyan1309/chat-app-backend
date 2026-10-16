package com.ayush.chat.tenant;

import com.ayush.chat.core.persistence.CsvSetConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
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
import java.util.LinkedHashSet;
import java.util.Set;

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

    /** Null fields mean unlimited. */
    @Embedded
    private TenantLimits limits;

    /** Toggleable feature ids included in the plan (core features are always on). */
    @Convert(converter = CsvSetConverter.class)
    @Column(name = "features")
    private Set<String> featureIds = new LinkedHashSet<>();

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

    public TenantLimits limitsOrNone() {
        return limits != null ? limits : TenantLimits.NONE;
    }
}
