package com.ayush.chat.admin.dto.request;

import java.util.Map;

public record FeatureOverridesRequest(Map<String, Boolean> overrides) {
}
