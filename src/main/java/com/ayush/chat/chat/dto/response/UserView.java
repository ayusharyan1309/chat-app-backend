package com.ayush.chat.chat.dto.response;

import com.ayush.chat.store.model.ChatUser;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserView(String id, String displayName, String email, String phone, String avatarUrl, String about,
                       Map<String, Object> metadata, boolean deactivated, Instant createdAt, Instant lastSeenAt,
                       Boolean online) {

    public static UserView of(ChatUser u, boolean includePresence, Boolean online) {
        return new UserView(u.getId(), u.getDisplayName(), u.getEmail(), u.getPhone(), u.getAvatarUrl(), u.getAbout(),
                u.getMetadata(), u.isDeactivated(), u.getCreatedAt(), includePresence ? u.getLastSeenAt() : null,
                includePresence ? online : null);
    }

    /** Minimal view for users that no longer exist in the store. */
    public static UserView unknown(String id) {
        return new UserView(id, id, null, null, null, null, null, true, null, null, null);
    }
}
