package com.ayush.chat.chat.dto.socket;

public record TypingFrame(String conversationId, boolean typing) {
}
