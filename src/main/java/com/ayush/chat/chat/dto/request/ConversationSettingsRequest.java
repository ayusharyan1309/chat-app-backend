package com.ayush.chat.chat.dto.request;

public record ConversationSettingsRequest(Boolean muted, Boolean archived, Boolean pinned) {
}
