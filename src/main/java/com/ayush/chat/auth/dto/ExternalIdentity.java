package com.ayush.chat.auth.dto;

public record ExternalIdentity(String userId, String displayName, String email) {
}
