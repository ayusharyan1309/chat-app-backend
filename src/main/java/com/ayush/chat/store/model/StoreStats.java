package com.ayush.chat.store.model;

/**
 * Entity counts. Providers where exact counts are expensive may return -1 for unknown.
 */
public record StoreStats(long users, long conversations, long messages) {
}
