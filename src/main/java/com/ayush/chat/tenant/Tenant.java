package com.ayush.chat.tenant;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
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

    public enum Status { ACTIVE, TRIAL, SUSPENDED, DELETED }

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

    @Column(name = "limits_json")
    private String limitsJson;

    @Column(name = "branding_json")
    private String brandingJson;

    @Column(name = "settings_json")
    private String settingsJson;

    @Column(name = "auth_json")
    private String authJson;

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
        return status == Status.ACTIVE || status == Status.TRIAL;
    }
}
