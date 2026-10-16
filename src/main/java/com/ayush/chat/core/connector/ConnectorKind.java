package com.ayush.chat.core.connector;

/**
 * The pluggable infrastructure slots of the platform. Each slot has exactly one active
 * provider at platform level; {@link #tenantOverridable} slots may be overridden per tenant.
 */
public enum ConnectorKind {

    MESSAGE_STORE("Message store", "Durable storage for users, conversations and messages", true),
    CACHE("Cache", "Presence, counters, rate limits and hot lookups", false),
    EVENT_BUS("Event bus", "Cross-node fan-out of deliveries and domain events", false),
    BLOB_STORE("Media storage", "Images, video, audio and file attachments", false),
    PUSH("Push notifications", "Notify offline users on their devices", true);

    private final String displayName;
    private final String description;
    private final boolean tenantOverridable;

    ConnectorKind(String displayName, String description, boolean tenantOverridable) {
        this.displayName = displayName;
        this.description = description;
        this.tenantOverridable = tenantOverridable;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public boolean isTenantOverridable() {
        return tenantOverridable;
    }

    /** Path-friendly id, e.g. {@code message-store}. */
    public String slug() {
        return name().toLowerCase().replace('_', '-');
    }

    public static ConnectorKind fromSlug(String slug) {
        for (ConnectorKind kind : values()) {
            if (kind.slug().equalsIgnoreCase(slug) || kind.name().equalsIgnoreCase(slug)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown connector kind: " + slug);
    }
}
