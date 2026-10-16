package com.ayush.chat.core.connector.config;

import com.ayush.chat.core.connector.ConnectorKind;

/**
 * Published (locally after commit, and on other nodes via polling) when a connector config row
 * changes.
 */
public record ConnectorConfigChangedEvent(ConfigScope scope, ConnectorKind kind) {
}
