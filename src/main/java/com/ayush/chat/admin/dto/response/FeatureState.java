package com.ayush.chat.admin.dto.response;

import com.ayush.chat.tenant.model.Feature;
import com.ayush.chat.tenant.model.FeatureSource;

public record FeatureState(String id, String name, String category, Feature.Status status, boolean enabled,
                           FeatureSource source) {
}
