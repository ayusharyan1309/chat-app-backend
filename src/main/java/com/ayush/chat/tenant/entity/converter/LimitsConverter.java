package com.ayush.chat.tenant.entity.converter;

import com.ayush.chat.core.persistence.JsonAttributeConverter;
import com.ayush.chat.tenant.model.TenantLimits;
import jakarta.persistence.Converter;

/** Maps the tenant's limits JSON column. */
@Converter
public class LimitsConverter extends JsonAttributeConverter<TenantLimits> {

    public LimitsConverter() {
        super(TenantLimits.class);
    }
}
