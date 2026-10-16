package com.ayush.chat.store.sql;

import java.sql.SQLException;

/**
 * A write was rejected by a unique or primary-key constraint ({@link SqlDialect#isUniqueViolation}).
 * Thrown by {@link SqlMessageStore}'s transaction helper so the operations that expect to lose a
 * race (idempotent sends, DIRECT conversation creation, first-time upserts) catch this type
 * directly. It never leaves the store.
 */
final class UniqueViolationException extends RuntimeException {

    UniqueViolationException(SQLException cause) {
        super(cause.getMessage(), cause);
    }
}
