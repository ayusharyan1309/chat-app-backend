package com.ayush.chat.chat.dto.response;

import java.time.Instant;

public record MinutePoint(Instant minute, long messages, long deliveries) {
}
