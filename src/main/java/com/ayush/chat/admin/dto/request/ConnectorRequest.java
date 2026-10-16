package com.ayush.chat.admin.dto.request;

import java.util.Map;

public record ConnectorRequest(String provider, Map<String, Object> settings) {
}
