package com.ayush.chat.saas.tenant;

import com.ayush.chat.saas.config.DatabaseType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

/**
 * Tenant entity representing an organization/company using the SaaS platform.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "saas_tenants")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_identifier", unique = true, nullable = false)
    private String tenantIdentifier;

    @Column(name = "tenant_name", nullable = false)
    private String tenantName;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "domain")
    private String domain;

    @Column(name = "contact_email", nullable = false)
    private String contactEmail;

    @Column(name = "contact_phone")
    private String contactPhone;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "primary_color")
    private String primaryColor;

    @Column(name = "secondary_color")
    private String secondaryColor;

    // Database configuration
    @Enumerated(EnumType.STRING)
    @Column(name = "database_type")
    private DatabaseType databaseType;

    @Column(name = "db_host")
    private String dbHost;

    @Column(name = "db_port")
    private Integer dbPort;

    @Column(name = "db_name")
    private String dbName;

    @Column(name = "db_username")
    private String dbUsername;

    @Column(name = "db_password")
    private String dbPassword;

    // Subscription & Limits
    @Column(name = "max_users")
    @Builder.Default
    private Integer maxUsers = 100;

    @Column(name = "max_storage_gb")
    @Builder.Default
    private Integer maxStorageGb = 10;

    @Column(name = "max_messages_per_day")
    @Builder.Default
    private Long maxMessagesPerDay = 10000L;

    @Column(name = "plan_type")
    @Builder.Default
    private String planType = "FREE";

    // Status
    @Column(name = "status")
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    // Timestamps
    @Column(name = "created_at", updatable = false)
    private Timestamp createdAt;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

    @Column(name = "trial_ends_at")
    private Timestamp trialEndsAt;

    @Column(name = "suspended_at")
    private Timestamp suspendedAt;

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
