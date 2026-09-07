package com.ayush.chat.saas.feature;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Available feature modules in the SaaS platform.
 * Each module can be independently enabled/disabled per tenant.
 */
public enum FeatureModule {
    // Core modules
    CORE_CHAT("core-chat", "Core Chat functionality", true),
    USER_MANAGEMENT("user-management", "User management and authentication", true),
    
    // Communication modules
    VOICE_CALLS("voice-calls", "Voice call support", false),
    VIDEO_CALLS("video-calls", "Video call support", false),
    SCREEN_SHARING("screen-sharing", "Screen sharing capability", false),
    FILE_SHARING("file-sharing", "File sharing in chat", false),
    MEDIA_SHARING("media-sharing", "Image/Video sharing", false),
    
    // Group features
    GROUP_CHAT("group-chat", "Group chat functionality", false),
    GROUP_CALLS("group-calls", "Conference calls", false),
    CHANNELS("channels", "Channel-based communication", false),
    
    // Integration modules
    KAFKA_INTEGRATION("kafka-integration", "Apache Kafka message broker", true),
    REDIS_CACHING("redis-caching", "Redis caching layer", false),
    ELASTICSEARCH("elasticsearch", "Elasticsearch for search", false),
    
    // Security modules
    TWO_FACTOR_AUTH("2fa", "Two-factor authentication", false),
    SSO_INTEGRATION("sso", "Single Sign-On integration", false),
    LDAP_AUTH("ldap", "LDAP authentication", false),
    OAUTH2("oauth2", "OAuth2 integration", false),
    
    // Analytics modules
    ANALYTICS("analytics", "Chat analytics dashboard", false),
    MESSAGE_ANALYTICS("message-analytics", "Message analytics", false),
    USER_ANALYTICS("user-analytics", "User activity analytics", false),
    
    // Customization modules
    CUSTOM_THEMING("custom-theming", "Custom branding and themes", false),
    WHITE_LABELING("white-labeling", "White-label solution", false),
    CUSTOM_EMOJIS("custom-emojis", "Custom emoji support", false),
    
    // Business modules
    TICKETING("ticketing", "Support ticket system", false),
    CRM_INTEGRATION("crm", "CRM integration", false),
    BOT_SUPPORT("bot-support", "Chatbot support", false),
    
    // Mobile modules
    MOBILE_PUSH("mobile-push", "Mobile push notifications", false),
    WEB_PUSH("web-push", "Web push notifications", false),
    
    // API modules
    PUBLIC_API("public-api", "Public REST API access", false),
    WEBHOOKS("webhooks", "Webhook support", false),
    
    // AI modules
    AI_TRANSLATION("ai-translation", "AI-powered translation", false),
    AI_SUMMARY("ai-summary", "AI message summary", false),
    AI_MODERATION("ai-moderation", "AI content moderation", false);

    private final String id;
    private final String description;
    private final boolean includedByDefault;

    FeatureModule(String id, String description, boolean includedByDefault) {
        this.id = id;
        this.description = description;
        this.includedByDefault = includedByDefault;
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public boolean isIncludedByDefault() {
        return includedByDefault;
    }

    /**
     * Gets all modules that are included by default.
     */
    public static List<FeatureModule> getDefaultModules() {
        return Arrays.stream(values())
                .filter(FeatureModule::isIncludedByDefault)
                .collect(Collectors.toList());
    }

    /**
     * Finds a feature module by its ID.
     */
    public static FeatureModule fromId(String id) {
        return Arrays.stream(values())
                .filter(m -> m.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown feature module: " + id));
    }
}
