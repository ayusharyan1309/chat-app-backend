package com.ayush.chat.chat.service;

import com.ayush.chat.store.MessageStore;
import com.ayush.chat.tenant.model.TenantSnapshot;

/** The tenant an operation acts for, resolved once per call, with the store holding its data. */
public record TenantScope(TenantSnapshot tenant, MessageStore store) {

    public String slug() {
        return tenant.slug();
    }
}
