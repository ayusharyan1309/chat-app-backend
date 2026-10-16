package com.ayush.chat.store.sql;

import java.sql.SQLException;

/**
 * The few places where H2, PostgreSQL and MySQL differ for this schema. Everything else is
 * portable SQL; timestamps are stored as epoch-millis BIGINTs to avoid timezone drift.
 */
public enum SqlDialect {

    H2("CHARACTER VARYING", true),
    POSTGRESQL("TEXT", true),
    MYSQL("MEDIUMTEXT", false);

    private final String textType;
    private final boolean createIndexIfNotExists;

    SqlDialect(String textType, boolean createIndexIfNotExists) {
        this.textType = textType;
        this.createIndexIfNotExists = createIndexIfNotExists;
    }

    public String textType() {
        return textType;
    }

    public boolean supportsCreateIndexIfNotExists() {
        return createIndexIfNotExists;
    }

    public static SqlDialect fromJdbcUrl(String url) {
        String u = url.toLowerCase();
        if (u.startsWith("jdbc:h2:")) return H2;
        if (u.startsWith("jdbc:postgresql:")) return POSTGRESQL;
        if (u.startsWith("jdbc:mysql:") || u.startsWith("jdbc:mariadb:")) return MYSQL;
        throw new IllegalArgumentException("Unsupported JDBC URL (expected h2, postgresql, mysql or mariadb): " + url);
    }

    /** Unique / primary-key violation (SQLSTATE class 23). */
    public static boolean isUniqueViolation(SQLException e) {
        for (SQLException cur = e; cur != null; cur = cur.getNextException()) {
            String state = cur.getSQLState();
            if (state != null && (state.equals("23505") || state.equals("23000") || state.equals("23001"))) {
                return true;
            }
            if (cur.getErrorCode() == 1062) {
                return true;
            }
        }
        return false;
    }

    /** Serialization failure or deadlock — safe to retry the whole transaction. */
    public static boolean isRetryable(SQLException e) {
        String state = e.getSQLState();
        return state != null && (state.equals("40001") || state.equals("40P01") || state.startsWith("40"));
    }

    /** Index/object already exists (only used for MySQL, which lacks CREATE INDEX IF NOT EXISTS). */
    public static boolean isAlreadyExists(SQLException e) {
        return e.getErrorCode() == 1061 || (e.getMessage() != null && e.getMessage().toLowerCase().contains("already exists"));
    }
}
