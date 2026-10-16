package com.ayush.chat.chat.dto.request;

public record IssueUserTokenRequest(String userId, Integer ttlMinutes) {
}
