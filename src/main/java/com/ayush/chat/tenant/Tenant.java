package com.ayush.chat.tenant;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A customer of the platform. {@link #slug} is the stable tenant id used in tokens and as
 * {@code tenant_id} in the data plane.
 */
@Getter
@Setter
@Entity
@Table(name = "platform_tenants")
public class Tenant {

    public enum Status {
        ACTIVE, TRIAL, SUSPENDED, DELETED;

        /** Whether users of a tenant in this state may connect and send. */
        public boolean isUsable() {
            return this == ACTIVE || this == TRIAL;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Column(name = "contact_email")
    private String contactEmail;

    private String domain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "plan_code", nullable = false)
    private String planCode;

    @Column(name = "trial_ends_at")
    private Instant trialEndsAt;

    /** Per-tenant overrides of the plan's limits (null fields inherit the plan). */
    @Convert(converter = TenantConfig.LimitsConverter.class)
    @Column(name = "limits_json")
    private TenantLimits limitOverrides;

    @Convert(converter = TenantConfig.BrandingConverter.class)
    @Column(name = "branding_json")
    private TenantConfig.Branding branding;

    @Convert(converter = TenantConfig.SettingsConverter.class)
    @Column(name = "settings_json")
    private TenantConfig.Settings settings;

    /** As stored: the external JWT secret is encrypted. */
    @Convert(converter = TenantConfig.AuthConverter.class)
    @Column(name = "auth_json")
    private TenantConfig.Auth auth;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @OneToMany(mappedBy = "tenant", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<TenantFeatureOverride> featureOverrides = new ArrayList<>();

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isUsable() {
        return status.isUsable();
    }

    public TenantLimits limitOverridesOrNone() {
        return limitOverrides != null ? limitOverrides : TenantLimits.NONE;
    }

    public TenantConfig.Branding brandingOrDefault() {
        return branding != null ? branding : TenantConfig.Branding.DEFAULT;
    }

    public TenantConfig.Settings effectiveSettings() {
        return (settings != null ? settings : TenantConfig.Settings.DEFAULT).withDefaults();
    }

    /** Stored auth config with defaults applied; the JWT secret is still encrypted. */
    public TenantConfig.Auth storedAuth() {
        return (auth != null ? auth : TenantConfig.Auth.DEFAULT).withDefaults();
    }
}
