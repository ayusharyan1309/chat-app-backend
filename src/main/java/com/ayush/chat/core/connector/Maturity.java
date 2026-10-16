package com.ayush.chat.core.connector;

/**
 * How battle-tested a connector is.
 */
public enum Maturity {
    /** Passes the conformance suite against a live backend in CI. */
    GA,
    /** Implements the contract and its conformance suite, but is not exercised against a live backend in CI. */
    BETA
}
