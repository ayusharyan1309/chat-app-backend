package com.ayush.chat.admin.dto.response;

import com.ayush.chat.core.connector.ConnectorDescriptor;

import java.util.List;

public record ConnectorCatalog(String kind, String displayName, String description, boolean tenantOverridable,
                               List<ConnectorDescriptor> providers) {
}
