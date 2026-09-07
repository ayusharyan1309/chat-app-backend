package com.ayush.chat.storage;

/**
 * Supported storage backend types for the chat platform.
 * 
 * Each type maps to a different storage provider implementation.
 * Switch the active backend by changing: chat.storage.type in application.yml
 */
public enum StorageType {
    
    /** MySQL - Relational SQL database */
    MYSQL("mysql", "MySQL", true),
    
    /** PostgreSQL - Advanced relational SQL database */
    POSTGRESQL("postgresql", "PostgreSQL", true),
    
    /** MongoDB - Document-oriented NoSQL database */
    MONGODB("mongodb", "MongoDB", true),
    
    /** Firebase Firestore - Google's serverless NoSQL document database */
    FIREBASE("firebase", "Firebase Firestore", true),
    
    /** Supabase - Open source Firebase alternative backed by PostgreSQL */
    SUPABASE("supabase", "Supabase", true),
    
    /** H2 - In-memory/embedded SQL database (dev/testing only) */
    H2("h2", "H2 Database", true),
    
    /** Redis - In-memory key-value store (for caching/realtime only) */
    REDIS("redis", "Redis", false);

    private final String code;
    private final String displayName;
    private final boolean supportsChatStorage;

    StorageType(String code, String displayName, boolean supportsChatStorage) {
        this.code = code;
        this.displayName = displayName;
        this.supportsChatStorage = supportsChatStorage;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean supportsChatStorage() {
        return supportsChatStorage;
    }

    /**
     * Find storage type by its code string (case-insensitive).
     */
    public static StorageType fromCode(String code) {
        for (StorageType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported storage type: " + code 
            + ". Supported types: MYSQL, POSTGRESQL, MONGODB, FIREBASE, SUPABASE, H2");
    }

    /**
     * Check if this storage type is SQL-based (relational).
     */
    public boolean isSqlBased() {
        return this == MYSQL || this == POSTGRESQL || this == H2;
    }

    /**
     * Check if this storage type is NoSQL-based (document/key-value).
     */
    public boolean isNoSqlBased() {
        return this == MONGODB || this == FIREBASE || this == REDIS;
    }
}
