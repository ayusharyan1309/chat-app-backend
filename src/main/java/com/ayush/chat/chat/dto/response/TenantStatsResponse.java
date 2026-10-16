package com.ayush.chat.chat.dto.response;

import com.ayush.chat.tenant.dto.DailyUsage;

import java.util.List;

public record TenantStatsResponse(long users, long conversations, long messages, long messagesToday, int connections,
                                  List<DailyUsage> usage) {
}
