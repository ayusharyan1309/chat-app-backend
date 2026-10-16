package com.ayush.chat.admin.dto.response;

import com.ayush.chat.tenant.model.Feature;

public record FeatureView(String id, String name, String description, String category, Feature.Status status) {
    public static FeatureView of(Feature f) {
        return new FeatureView(f.getId(), f.getDisplayName(), f.getDescription(), f.getCategory(), f.getStatus());
    }
}
