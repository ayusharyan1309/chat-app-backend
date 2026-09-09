package com.ayush.chat.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the StorageType enum.
 * Validates all enum values, fromCode resolution, SQL/NoSQL classification,
 * chat storage support flags, and display metadata.
 */
class StorageTypeTest {

    // ========== Enum Values ==========

    @Test
    void shouldHaveExactlySevenStorageTypes() {
        assertEquals(7, StorageType.values().length,
                "StorageType should have exactly 7 enum values");
    }

    @Test
    void shouldContainAllExpectedTypes() {
        List<StorageType> expected = Arrays.asList(
                StorageType.MYSQL, StorageType.POSTGRESQL, StorageType.MONGODB,
                StorageType.FIREBASE, StorageType.SUPABASE, StorageType.H2, StorageType.REDIS
        );
        assertTrue(Arrays.asList(StorageType.values()).containsAll(expected));
    }

    // ========== fromCode() Resolution ==========

    @ParameterizedTest(name = "fromCode(\"{0}\") should return {1}")
    @CsvSource({
            "mysql, MYSQL",
            "MYSQL, MYSQL",
            "Mysql, MYSQL",
            "postgresql, POSTGRESQL",
            "POSTGRESQL, POSTGRESQL",
            "mongodb, MONGODB",
            "MONGODB, MONGODB",
            "firebase, FIREBASE",
            "FIREBASE, FIREBASE",
            "supabase, SUPABASE",
            "SUPABASE, SUPABASE",
            "h2, H2",
            "H2, H2",
            "redis, REDIS",
            "REDIS, REDIS"
    })
    void fromCodeShouldBeCaseInsensitive(String code, StorageType expected) {
        assertEquals(expected, StorageType.fromCode(code),
                "fromCode should resolve case-insensitively for: " + code);
    }

    @Test
    void fromCodeShouldThrowForUnsupportedType() {
        assertThrows(IllegalArgumentException.class, () -> StorageType.fromCode("cassandra"));
        assertThrows(IllegalArgumentException.class, () -> StorageType.fromCode("neo4j"));
        assertThrows(IllegalArgumentException.class, () -> StorageType.fromCode(""));
        assertThrows(IllegalArgumentException.class, () -> StorageType.fromCode("sqlite"));
    }

    // ========== Code, DisplayName, and supportsChatStorage ==========

    @ParameterizedTest(name = "{0}: code={1}, displayName={2}, supportsChatStorage={3}")
    @CsvSource({
            "MYSQL, mysql, MySQL, true",
            "POSTGRESQL, postgresql, PostgreSQL, true",
            "MONGODB, mongodb, MongoDB, true",
            "FIREBASE, firebase, Firebase Firestore, true",
            "SUPABASE, supabase, Supabase, true",
            "H2, h2, H2 Database, true",
            "REDIS, redis, Redis, false"
    })
    void shouldHaveCorrectMetadata(StorageType type, String code, String displayName, boolean supportsChat) {
        assertEquals(code, type.getCode(), "Code mismatch for " + type);
        assertEquals(displayName, type.getDisplayName(), "Display name mismatch for " + type);
        assertEquals(supportsChat, type.supportsChatStorage(), "Chat storage support mismatch for " + type);
    }

    // ========== isSqlBased() Classification ==========

    @Test
    void mysqlShouldBeSqlBased() {
        assertTrue(StorageType.MYSQL.isSqlBased());
    }

    @Test
    void postgresqlShouldBeSqlBased() {
        assertTrue(StorageType.POSTGRESQL.isSqlBased());
    }

    @Test
    void h2ShouldBeSqlBased() {
        assertTrue(StorageType.H2.isSqlBased());
    }

    @Test
    void mongodbShouldNotBeSqlBased() {
        assertFalse(StorageType.MONGODB.isSqlBased());
    }

    @Test
    void firebaseShouldNotBeSqlBased() {
        assertFalse(StorageType.FIREBASE.isSqlBased());
    }

    @Test
    void supabaseShouldNotBeSqlBased() {
        assertFalse(StorageType.SUPABASE.isSqlBased());
    }

    @Test
    void redisShouldNotBeSqlBased() {
        assertFalse(StorageType.REDIS.isSqlBased());
    }

    // ========== isNoSqlBased() Classification ==========

    @Test
    void mongodbShouldBeNoSqlBased() {
        assertTrue(StorageType.MONGODB.isNoSqlBased());
    }

    @Test
    void firebaseShouldBeNoSqlBased() {
        assertTrue(StorageType.FIREBASE.isNoSqlBased());
    }

    @Test
    void redisShouldBeNoSqlBased() {
        assertTrue(StorageType.REDIS.isNoSqlBased());
    }

    @Test
    void mysqlShouldNotBeNoSqlBased() {
        assertFalse(StorageType.MYSQL.isNoSqlBased());
    }

    @Test
    void postgresqlShouldNotBeNoSqlBased() {
        assertFalse(StorageType.POSTGRESQL.isNoSqlBased());
    }

    @Test
    void h2ShouldNotBeNoSqlBased() {
        assertFalse(StorageType.H2.isNoSqlBased());
    }

    @Test
    void supabaseShouldNotBeNoSqlBased() {
        assertFalse(StorageType.SUPABASE.isNoSqlBased());
    }

    // ========== Chat Storage Support ==========

    @ParameterizedTest
    @EnumSource(value = StorageType.class, names = {"MYSQL", "POSTGRESQL", "MONGODB", "FIREBASE", "SUPABASE", "H2"})
    void allSupportedTypesShouldSupportChatStorage(StorageType type) {
        assertTrue(type.supportsChatStorage(), type + " should support chat storage");
    }

    @Test
    void redisShouldNotSupportChatStorage() {
        assertFalse(StorageType.REDIS.supportsChatStorage(),
                "Redis should not support chat storage (caching/realtime only)");
    }

    // ========== Consistency Checks ==========

    @Test
    void isSqlBasedAndIsNoSqlBasedShouldBeMutuallyExclusive() {
        for (StorageType type : StorageType.values()) {
            if (type.isSqlBased()) {
                assertFalse(type.isNoSqlBased(), type + " cannot be both SQL and NoSQL");
            }
            if (type.isNoSqlBased()) {
                assertFalse(type.isSqlBased(), type + " cannot be both SQL and NoSQL");
            }
        }
    }

    @Test
    void everyTypeShouldBeEitherSqlOrNoSqlOrRedis() {
        for (StorageType type : StorageType.values()) {
            if (type == StorageType.SUPABASE) {
                // Supabase is backed by PostgreSQL but accessed via REST API
                assertFalse(type.isSqlBased(),
                        "Supabase should not be SQL-based (uses REST API)");
                assertFalse(type.isNoSqlBased(),
                        "Supabase should not be NoSQL-based (backed by PostgreSQL)");
            } else {
                assertTrue(type.isSqlBased() || type.isNoSqlBased(),
                        type + " should be classified as SQL or NoSQL");
            }
        }
    }

    @Test
    void codeShouldBeUniqueForEachType() {
        Set<String> codes = new java.util.HashSet<>();
        for (StorageType type : StorageType.values()) {
            assertTrue(codes.add(type.getCode()),
                    "Duplicate code found: " + type.getCode());
        }
    }

    @Test
    void displayNameShouldBeUniqueForEachType() {
        Set<String> names = new java.util.HashSet<>();
        for (StorageType type : StorageType.values()) {
            assertTrue(names.add(type.getDisplayName()),
                    "Duplicate display name found: " + type.getDisplayName());
        }
    }
}
