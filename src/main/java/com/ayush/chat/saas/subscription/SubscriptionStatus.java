package com.ayush.chat.saas.subscription;

/**
 * Possible states for a tenant subscription.
 */
public enum SubscriptionStatus {
    ACTIVE,
    SUSPENDED,
    CANCELLED,
    EXPIRED,
    PENDING,
    TRIAL,
    PAST_DUE
}
