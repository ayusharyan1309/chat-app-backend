package com.ayush.chat.store.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * An end user of a tenant's app. {@link #id} is chosen by the tenant (their own user id).
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ChatUser {
    private String tenantId;
    private String id;
    private String displayName;
    private String email;
    private String phone;
    private String avatarUrl;
    private String about;
    /** Device tokens for push notifications. */
    @Builder.Default
    private Set<String> pushTokens = new LinkedHashSet<>();
    private Map<String, Object> metadata;
    private boolean deactivated;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastSeenAt;
}
