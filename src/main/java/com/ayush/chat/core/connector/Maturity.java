package com.ayush.chat.core.connector;

/**
 * How battle-tested a connector is.
 */
public enum Maturity {
    /**
     * Passes its conformance suite against the real client library: a live backend in CI (databases,
     * Redis, Kafka) or a protocol-level fake of the vendor API (S3, FCM, HTTP webhooks).
     */
    GA,
    /** Implements the contract and its conformance suite, but is not exercised against a live backend in CI. */
    BETA
}
