package com.ayush.chat.chat.application;

import com.ayush.chat.store.MessageStore;
import com.ayush.chat.tenant.TenantSnapshot;

/** The tenant an operation acts for, resolved once per call, with the store holding its data. */
public record TenantScope(TenantSnapshot tenant, MessageStore store) {

    public String slug() {
        return tenant.slug();
    }
}
