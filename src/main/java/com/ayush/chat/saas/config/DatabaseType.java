package com.ayush.chat.saas.config;

/**
 * Supported database types for multi-tenant SaaS platform.
 */
public enum DatabaseType {
    MYSQL("mysql", "com.mysql.cj.jdbc.Driver", "jdbc:mysql://%s:%d/%s"),
    POSTGRESQL("postgresql", "org.postgresql.Driver", "jdbc:postgresql://%s:%d/%s"),
    H2("h2", "org.h2.Driver", "jdbc:h2:mem:%s"),
    SQLSERVER("sqlserver", "com.microsoft.sqlserver.jdbc.SQLServerDriver", "jdbc:sqlserver://%s:%d;databaseName=%s"),
    ORACLE("oracle", "oracle.jdbc.OracleDriver", "jdbc:oracle:thin:@%s:%d:%s"),
    MONGODB("mongodb", "", "mongodb://%s:%d/%s");

    private final String name;
    private final String driverClassName;
    private final String urlPattern;

    DatabaseType(String name, String driverClassName, String urlPattern) {
        this.name = name;
        this.driverClassName = driverClassName;
        this.urlPattern = urlPattern;
    }

    public String getName() {
        return name;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    public String getUrlPattern() {
        return urlPattern;
    }

    /**
     * Generates JDBC URL based on the pattern.
     */
    public String generateUrl(String host, int port, String database) {
        return String.format(urlPattern, host, port, database);
    }

    /**
     * Finds database type by name.
     */
    public static DatabaseType fromName(String name) {
        for (DatabaseType type : values()) {
            if (type.name.equalsIgnoreCase(name)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported database type: " + name);
    }
}
