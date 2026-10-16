package com.ayush.chat.chat.dto.response;

import java.time.Instant;

public record LastMessage(String id, String preview, String senderId, Instant at) {
}
