package com.ayush.chat.core.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A persisted connector configuration for one scope ("platform" or "tenant:{slug}") and kind.
 * Secret values inside {@link #settingsJson} are AES-GCM encrypted.
 */
@Getter
@Setter
@Entity
@Table(name = "platform_connector_configs")
public class ConnectorConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scope_key", nullable = false)
    private String scopeKey;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String provider;

    @Column(name = "settings_json")
    private String settingsJson;

    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;
}
