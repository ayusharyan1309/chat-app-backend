package com.ayush.chat.tenant.entity.converter;

import com.ayush.chat.core.persistence.JsonAttributeConverter;
import com.ayush.chat.tenant.model.TenantConfig;
import jakarta.persistence.Converter;

/** Maps the tenant's auth JSON column. */
@Converter
public class AuthConverter extends JsonAttributeConverter<TenantConfig.Auth> {

    public AuthConverter() {
        super(TenantConfig.Auth.class);
    }
}
