package com.ayush.chat.tenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Explicit per-tenant feature switch that wins over the plan's feature bundle.
 */
@Getter
@Setter
@Entity
@Table(name = "platform_tenant_features")
public class TenantFeatureOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "feature_id", nullable = false)
    private String featureId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
