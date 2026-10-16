package com.ayush.chat.tenant.entity.converter;

import com.ayush.chat.core.persistence.JsonAttributeConverter;
import com.ayush.chat.tenant.model.TenantConfig;
import jakarta.persistence.Converter;

/** Maps the tenant's settings JSON column. */
@Converter
public class SettingsConverter extends JsonAttributeConverter<TenantConfig.Settings> {

    public SettingsConverter() {
        super(TenantConfig.Settings.class);
    }
}
