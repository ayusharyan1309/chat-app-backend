package com.ayush.chat.webhook.dto;

import java.util.List;

public record WebhookRequest(String url, List<String> events, String description, Boolean active) {
}
