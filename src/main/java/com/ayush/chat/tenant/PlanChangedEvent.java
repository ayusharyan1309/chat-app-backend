package com.ayush.chat.tenant;

/** A plan's limits or features changed: every node must drop all cached tenant snapshots. */
public record PlanChangedEvent(String code) {
}
