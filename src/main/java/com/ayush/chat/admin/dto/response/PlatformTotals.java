package com.ayush.chat.admin.dto.response;

/** {@code -1} store counts mean the platform store could not be reached. */
public record PlatformTotals(long tenants, long activeTenants, long users, long conversations, long messages, int connections,
                             long messagesToday) {
}
