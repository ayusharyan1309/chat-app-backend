package com.ayush.chat.store;

import com.ayush.chat.core.exception.InfrastructureUnavailableException;

/**
 * Small helpers shared by the {@link MessageStore} adapters.
 */
public final class StoreSupport {

    private StoreSupport() {
    }

    /** {@code head} followed by {@code tail}, e.g. shared column values plus a statement's key columns. */
    public static Object[] concat(Object[] head, Object... tail) {
        Object[] all = new Object[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    /**
     * Waits before retrying against a concurrent writer. An interrupt (typically shutdown) keeps the
     * thread's interrupt flag and aborts the operation as a retryable 503.
     */
    public static void backoff(String component, long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InfrastructureUnavailableException(component, e);
        }
    }
}
