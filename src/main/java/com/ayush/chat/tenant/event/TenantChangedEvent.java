package com.ayush.chat.tenant.event;

/** Published inside the transaction that changed a tenant; listeners act after commit. */
public record TenantChangedEvent(String slug) {
}
