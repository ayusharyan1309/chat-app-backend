package com.ayush.chat.tenant.model;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Feature catalog. {@code CORE} features are always on; {@code AVAILABLE} features are sold via
 * plans and can be overridden per tenant; {@code PLANNED} features are listed for roadmap
 * visibility and cannot be enabled.
 */
public enum Feature {

    // Core — always on
    DIRECT_MESSAGING("direct-messaging", "1:1 messaging", "Messaging", Status.CORE,
            "One-to-one conversations with ordered, idempotent delivery and offline sync"),
    MESSAGE_REPLIES("message-replies", "Replies & forwards", "Messaging", Status.CORE,
            "Quote-reply to a message and mark messages as forwarded"),
    USER_BLOCKING("user-blocking", "User blocking", "Safety", Status.CORE,
            "Users can block others; blocked senders cannot deliver messages"),

    // Plan features
    GROUP_CHAT("group-chat", "Group chat", "Messaging", Status.AVAILABLE,
            "Group conversations with owners/admins, membership management and system messages"),
    READ_RECEIPTS("read-receipts", "Read receipts", "Messaging", Status.AVAILABLE,
            "Delivered/read watermarks per participant with real-time receipt events"),
    TYPING_INDICATORS("typing-indicators", "Typing indicators", "Realtime", Status.AVAILABLE,
            "Ephemeral 'is typing' events"),
    PRESENCE("presence", "Presence", "Realtime", Status.AVAILABLE,
            "Online status and last-seen timestamps"),
    MESSAGE_REACTIONS("message-reactions", "Reactions", "Messaging", Status.AVAILABLE,
            "Emoji reactions on messages"),
    MESSAGE_EDITING("message-editing", "Message editing", "Messaging", Status.AVAILABLE,
            "Edit sent messages within a configurable window"),
    MESSAGE_DELETION("message-deletion", "Delete for everyone", "Messaging", Status.AVAILABLE,
            "Retract a sent message for all participants"),
    MEDIA_SHARING("media-sharing", "Media sharing", "Media", Status.AVAILABLE,
            "Images, video and voice notes via the configured media store"),
    FILE_SHARING("file-sharing", "File sharing", "Media", Status.AVAILABLE,
            "Documents and arbitrary file attachments"),
    PUSH_NOTIFICATIONS("push-notifications", "Push notifications", "Engagement", Status.AVAILABLE,
            "Notify offline users through the configured push provider"),
    WEBHOOKS("webhooks", "Webhooks", "Integration", Status.AVAILABLE,
            "HMAC-signed HTTP callbacks for message and conversation events"),
    EXTERNAL_AUTH("external-auth", "Bring your own auth", "Integration", Status.AVAILABLE,
            "Accept Firebase ID tokens or your own JWTs (shared secret / JWKS)"),
    ANALYTICS("analytics", "Usage analytics", "Insights", Status.AVAILABLE,
            "Usage and engagement statistics via the server API"),
    CUSTOM_BRANDING("custom-branding", "Custom branding", "Customization", Status.AVAILABLE,
            "Tenant logo, colours and display name served to client apps"),

    // Roadmap
    VOICE_CALLS("voice-calls", "Voice calls", "Calling", Status.PLANNED, "WebRTC voice calling"),
    VIDEO_CALLS("video-calls", "Video calls", "Calling", Status.PLANNED, "WebRTC video calling"),
    CHANNELS("channels", "Broadcast channels", "Messaging", Status.PLANNED, "One-to-many broadcast channels"),
    MESSAGE_SEARCH("message-search", "Full-text search", "Messaging", Status.PLANNED, "Search across message history"),
    AI_MODERATION("ai-moderation", "AI moderation", "AI", Status.PLANNED, "Automatic content moderation"),
    AI_TRANSLATION("ai-translation", "AI translation", "AI", Status.PLANNED, "Inline message translation"),
    SSO("sso", "Admin SSO", "Security", Status.PLANNED, "SAML/OIDC single sign-on for the dashboard");

    public enum Status { CORE, AVAILABLE, PLANNED }

    private final String id;
    private final String displayName;
    private final String category;
    private final Status status;
    private final String description;

    Feature(String id, String displayName, String category, Status status, String description) {
        this.id = id;
        this.displayName = displayName;
        this.category = category;
        this.status = status;
        this.description = description;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getCategory() { return category; }
    public Status getStatus() { return status; }
    public String getDescription() { return description; }

    public boolean isCore() { return status == Status.CORE; }
    public boolean isToggleable() { return status == Status.AVAILABLE; }

    public static Optional<Feature> fromId(String id) {
        return Arrays.stream(values()).filter(f -> f.id.equalsIgnoreCase(id)).findFirst();
    }

    public static List<Feature> core() {
        return Arrays.stream(values()).filter(Feature::isCore).toList();
    }
}
