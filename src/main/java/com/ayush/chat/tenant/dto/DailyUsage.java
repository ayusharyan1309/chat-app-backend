package com.ayush.chat.tenant.dto;

public record DailyUsage(String day, long messages, long mediaBytes, long apiCalls) {
}
