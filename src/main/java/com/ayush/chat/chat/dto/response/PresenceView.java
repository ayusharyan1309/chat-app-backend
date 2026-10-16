package com.ayush.chat.chat.dto.response;

import java.time.Instant;

public record PresenceView(String userId, boolean online, Instant lastSeenAt) {
}
