package com.ayush.chat.admin.dto.response;

/** {@code -1} counts mean the tenant's store could not be reached. */
public record TenantStats(long users, long conversations, long messages, long messagesToday, int connections, long storageBytes) {
}
