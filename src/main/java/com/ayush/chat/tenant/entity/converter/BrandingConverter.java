package com.ayush.chat.tenant.entity.converter;

import com.ayush.chat.core.persistence.JsonAttributeConverter;
import com.ayush.chat.tenant.model.TenantConfig;
import jakarta.persistence.Converter;

/** Maps the tenant's branding JSON column. */
@Converter
public class BrandingConverter extends JsonAttributeConverter<TenantConfig.Branding> {

    public BrandingConverter() {
        super(TenantConfig.Branding.class);
    }
}
