package com.ayush.chat.storage.platform;

import com.ayush.chat.storage.dto.PlatformDbRequest;
import com.ayush.chat.storage.dto.UserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PlatformUserServiceTest {

    private PlatformUserService service;

    @BeforeEach
    void setUp() {
        service = new PlatformUserService();
    }

    // ==================== CRUD Operations ====================

    @Nested
    @DisplayName("CRUD Operations")
    class CrudOperations {

        @Test
        @DisplayName("should save and retrieve a platform")
        void shouldSaveAndRetrievePlatform() {
            PlatformDbRequest request = PlatformDbRequest.builder()
                    .id("test_mysql")
                    .name("Test MySQL")
                    .type("mysql")
                    .active(true)
                    .sql(PlatformDbRequest.SqlPlatformConfig.builder()
                            .url("jdbc:h2:mem:test")
                            .username("sa")
                            .password("")
                            .build())
                    .build();

            service.savePlatform(request);

            Optional<PlatformDbConfig> retrieved = service.getPlatform("test_mysql");
            assertTrue(retrieved.isPresent(), "Platform should exist after save");
            assertEquals("Test MySQL", retrieved.get().getName());
            assertEquals("mysql", retrieved.get().getType());
            assertTrue(retrieved.get().isActive());
        }

        @Test
        @DisplayName("should update existing platform on re-save")
        void shouldUpdateExistingPlatformOnResave() {
            PlatformDbRequest initial = PlatformDbRequest.builder()
                    .id("upd_platform")
                    .name("Original Name")
                    .type("mysql")
                    .active(true)
                    .build();
            service.savePlatform(initial);

            PlatformDbRequest updated = PlatformDbRequest.builder()
                    .id("upd_platform")
                    .name("Updated Name")
                    .type("mysql")
                    .active(false)
                    .build();
            service.savePlatform(updated);

            Optional<PlatformDbConfig> result = service.getPlatform("upd_platform");
            assertTrue(result.isPresent());
            assertEquals("Updated Name", result.get().getName());
            assertFalse(result.get().isActive());
        }

        @Test
        @DisplayName("should return all platforms")
        void shouldReturnAllPlatforms() {
            service.savePlatform(PlatformDbRequest.builder()
                    .id("p1").name("Platform 1").type("mysql").active(true).build());
            service.savePlatform(PlatformDbRequest.builder()
                    .id("p2").name("Platform 2").type("supabase").active(true).build());
            service.savePlatform(PlatformDbRequest.builder()
                    .id("p3").name("Platform 3").type("mongodb").active(false).build());

            List<PlatformDbRequest> all = service.getAllPlatforms();
            assertEquals(3, all.size());
        }

        @Test
        @DisplayName("should remove a platform")
        void shouldRemovePlatform() {
            service.savePlatform(PlatformDbRequest.builder()
                    .id("to_remove").name("Remove Me").type("mysql").active(true).build());

            assertTrue(service.removePlatform("to_remove"), "Should return true when platform exists");
            assertFalse(service.getPlatform("to_remove").isPresent(), "Platform should be gone");
        }

        @Test
        @DisplayName("should return false when removing non-existent platform")
        void shouldReturnFalseForNonExistentPlatformRemoval() {
            assertFalse(service.removePlatform("nonexistent"));
        }

        @Test
        @DisplayName("should return Optional.empty for non-existent platform")
        void shouldReturnEmptyForNonExistentPlatform() {
            assertFalse(service.getPlatform("nonexistent").isPresent());
        }
    }

    // ==================== SQL User Fetching ====================

    @Nested
    @DisplayName("SQL User Fetching")
    class SqlUserFetching {

        private String h2Url;

        @BeforeEach
        void setUpH2() throws Exception {
            h2Url = "jdbc:h2:mem:sql_user_fetch_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";

            // Create the users table and seed data
            try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE users (id VARCHAR(50), email VARCHAR(255), full_name VARCHAR(255), profile_url VARCHAR(500))");
                stmt.execute("INSERT INTO users VALUES ('u1', 'alice@example.com', 'Alice Smith', 'https://avatar.com/alice')");
                stmt.execute("INSERT INTO users VALUES ('u2', 'bob@example.com', 'Bob Jones', 'https://avatar.com/bob')");
                stmt.execute("INSERT INTO users VALUES ('u3', 'carol@test.org', 'Carol White', NULL)");
            }

            service.savePlatform(PlatformDbRequest.builder()
                    .id("h2_platform")
                    .name("H2 Platform")
                    .type("mysql")
                    .active(true)
                    .sql(PlatformDbRequest.SqlPlatformConfig.builder()
                            .url(h2Url)
                            .username("sa")
                            .password("")
                            .build())
                    .build());
        }

        @Test
        @DisplayName("should fetch all users from SQL platform")
        void shouldFetchAllUsersFromSql() {
            List<UserResponse> users = service.getUsersFromPlatform("h2_platform", null);
            assertEquals(3, users.size(), "Should fetch all 3 users");
        }

        @Test
        @DisplayName("should fetch users by email query")
        void shouldFetchUsersByEmailQuery() {
            List<UserResponse> users = service.getUsersFromPlatform("h2_platform", "alice");
            assertEquals(1, users.size());
            assertEquals("alice@example.com", users.get(0).getEmail());
        }

        @Test
        @DisplayName("should fetch users by name query")
        void shouldFetchUsersByNameQuery() {
            List<UserResponse> users = service.getUsersFromPlatform("h2_platform", "Jones");
            assertEquals(1, users.size());
            assertEquals("bob@example.com", users.get(0).getEmail());
        }

        @Test
        @DisplayName("should return empty list for non-matching query")
        void shouldReturnEmptyForNonMatchingQuery() {
            List<UserResponse> users = service.getUsersFromPlatform("h2_platform", "zzzznotfound");
            assertTrue(users.isEmpty());
        }

        @Test
        @DisplayName("should set platformId on fetched users")
        void shouldSetPlatformIdOnFetchedUsers() {
            List<UserResponse> users = service.getUsersFromPlatform("h2_platform", null);
            users.forEach(u -> assertEquals("h2_platform", u.getPlatformId()));
        }
    }

    // ==================== Connection Testing ====================

    @Nested
    @DisplayName("Connection Testing")
    class ConnectionTesting {

        @Test
        @DisplayName("should test SQL connection successfully")
        void shouldTestSqlConnectionSuccessfully() {
            String h2Url = "jdbc:h2:mem:conn_test_" + System.nanoTime();
            service.savePlatform(PlatformDbRequest.builder()
                    .id("sql_conn")
                    .name("SQL Conn Test")
                    .type("mysql")
                    .active(true)
                    .sql(PlatformDbRequest.SqlPlatformConfig.builder()
                            .url(h2Url)
                            .username("sa")
                            .password("")
                            .build())
                    .build());

            Map<String, Object> result = service.testPlatformConnection("sql_conn");
            assertEquals(true, result.get("healthy"));
            assertEquals("Connection successful", result.get("message"));
        }

        @Test
        @DisplayName("should report unhealthy for bad SQL connection")
        void shouldReportUnhealthyForBadSqlConnection() {
            service.savePlatform(PlatformDbRequest.builder()
                    .id("bad_sql")
                    .name("Bad SQL")
                    .type("mysql")
                    .active(true)
                    .sql(PlatformDbRequest.SqlPlatformConfig.builder()
                            .url("jdbc:hsqldb:mem:nonexistent_bad_url_xxxx")
                            .username("sa")
                            .password("")
                            .build())
                    .build());

            Map<String, Object> result = service.testPlatformConnection("bad_sql");
            assertEquals(false, result.get("healthy"));
        }

        @Test
        @DisplayName("should report not found for unknown platform")
        void shouldReportNotFoundForUnknownPlatform() {
            Map<String, Object> result = service.testPlatformConnection("unknown");
            assertEquals(false, result.get("healthy"));
            assertTrue(result.get("message").toString().contains("not found"));
        }

        @Test
        @DisplayName("should test MongoDB connection (configured)")
        void shouldTestMongoConnection() {
            service.savePlatform(PlatformDbRequest.builder()
                    .id("mongo_conn")
                    .name("Mongo Conn")
                    .type("mongodb")
                    .active(true)
                    .mongodb(PlatformDbRequest.MongoPlatformConfig.builder()
                            .uri("mongodb://localhost:27017")
                            .database("test")
                            .build())
                    .build());

            Map<String, Object> result = service.testPlatformConnection("mongo_conn");
            assertEquals(true, result.get("healthy"));
        }

        @Test
        @DisplayName("should test Firebase connection (configured)")
        void shouldTestFirebaseConnection() {
            service.savePlatform(PlatformDbRequest.builder()
                    .id("firebase_conn")
                    .name("Firebase Conn")
                    .type("firebase")
                    .active(true)
                    .firebase(PlatformDbRequest.FirebasePlatformConfig.builder()
                            .projectId("my-project-id")
                            .build())
                    .build());

            Map<String, Object> result = service.testPlatformConnection("firebase_conn");
            assertEquals(true, result.get("healthy"));
        }

        @Test
        @DisplayName("should report unhealthy for Firebase with no project ID")
        void shouldReportUnhealthyForFirebaseWithNoProjectId() {
            service.savePlatform(PlatformDbRequest.builder()
                    .id("firebase_no_pid")
                    .name("Firebase No PID")
                    .type("firebase")
                    .active(true)
                    .firebase(PlatformDbRequest.FirebasePlatformConfig.builder()
                            .build())
                    .build());

            Map<String, Object> result = service.testPlatformConnection("firebase_no_pid");
            assertEquals(false, result.get("healthy"));
        }
    }

    // ==================== User Fetching Edge Cases ====================

    @Nested
    @DisplayName("User Fetching Edge Cases")
    class UserFetchingEdgeCases {

        @Test
        @DisplayName("should throw for non-existent platform")
        void shouldThrowForNonExistentPlatform() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getUsersFromPlatform("nonexistent", null));
        }

        @Test
        @DisplayName("should return empty for inactive platform")
        void shouldReturnEmptyForInactivePlatform() {
            service.savePlatform(PlatformDbRequest.builder()
                    .id("inactive_sql")
                    .name("Inactive")
                    .type("mysql")
                    .active(false)
                    .sql(PlatformDbRequest.SqlPlatformConfig.builder()
                            .url("jdbc:h2:mem:inactive")
                            .username("sa")
                            .password("")
                            .build())
                    .build());

            List<UserResponse> users = service.getUsersFromPlatform("inactive_sql", null);
            assertTrue(users.isEmpty());
        }

        @Test
        @DisplayName("should return empty for unsupported platform type")
        void shouldReturnEmptyForUnsupportedType() {
            service.savePlatform(PlatformDbRequest.builder()
                    .id("unsupported")
                    .name("Weird Type")
                    .type("couchdb")
                    .active(true)
                    .build());

            List<UserResponse> users = service.getUsersFromPlatform("unsupported", null);
            assertTrue(users.isEmpty());
        }
    }

    // ==================== getAllUsers & Deduplication ====================

    @Nested
    @DisplayName("getAllUsers and Deduplication")
    class GetAllUsersAndDeduplication {

        private String h2Url;

        @BeforeEach
        void setUp() throws Exception {
            long ts = System.nanoTime();

            // Platform 1: MySQL with Alice and Bob
            h2Url = "jdbc:h2:mem:allusers_p1_" + ts + ";DB_CLOSE_DELAY=-1";
            try (Connection conn = DriverManager.getConnection(h2Url, "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE users (id VARCHAR(50), email VARCHAR(255), full_name VARCHAR(255), profile_url VARCHAR(500))");
                stmt.execute("INSERT INTO users VALUES ('u1', 'alice@example.com', 'Alice', NULL)");
                stmt.execute("INSERT INTO users VALUES ('u2', 'bob@example.com', 'Bob', NULL)");
            }

            // Platform 2: MySQL with Bob (duplicate) and Carol
            String h2Url2 = "jdbc:h2:mem:allusers_p2_" + ts + ";DB_CLOSE_DELAY=-1";
            try (Connection conn = DriverManager.getConnection(h2Url2, "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE users (id VARCHAR(50), email VARCHAR(255), full_name VARCHAR(255), profile_url VARCHAR(500))");
                stmt.execute("INSERT INTO users VALUES ('u5', 'bob@example.com', 'Bob Jones', NULL)");
                stmt.execute("INSERT INTO users VALUES ('u3', 'carol@test.org', 'Carol', NULL)");
            }

            service.savePlatform(PlatformDbRequest.builder()
                    .id("dedup_p1").name("P1").type("mysql").active(true)
                    .sql(PlatformDbRequest.SqlPlatformConfig.builder().url(h2Url).username("sa").password("").build())
                    .build());

            service.savePlatform(PlatformDbRequest.builder()
                    .id("dedup_p2").name("P2").type("mysql").active(true)
                    .sql(PlatformDbRequest.SqlPlatformConfig.builder().url(h2Url2).username("sa").password("").build())
                    .build());
        }

        @Test
        @DisplayName("should deduplicate users by email across platforms")
        void shouldDeduplicateUsersByEmail() {
            List<UserResponse> allUsers = service.getAllUsers(null);
            // Alice + Bob (first occurrence) + Carol = 3 unique
            assertEquals(3, allUsers.size(), "Should deduplicate Bob across platforms");
        }

        @Test
        @DisplayName("should filter deduplicated users by query")
        void shouldFilterDeduplicatedUsersByQuery() {
            List<UserResponse> allUsers = service.getAllUsers("alice");
            assertEquals(1, allUsers.size());
            assertEquals("alice@example.com", allUsers.get(0).getEmail());
        }

        @Test
        @DisplayName("should skip inactive platforms in getAllUsers")
        void shouldSkipInactivePlatforms() {
            // Deactivate p2
            service.savePlatform(PlatformDbRequest.builder()
                    .id("dedup_p2").name("P2").type("mysql").active(false)
                    .sql(PlatformDbRequest.SqlPlatformConfig.builder().url("jdbc:h2:mem:dummy").username("sa").password("").build())
                    .build());

            List<UserResponse> allUsers = service.getAllUsers(null);
            // Only from p1: Alice + Bob = 2
            assertEquals(2, allUsers.size());
        }
    }

    // ==================== Supabase Config Validation ====================

    @Nested
    @DisplayName("Supabase Config Validation")
    class SupabaseConfigValidation {

        @Test
        @DisplayName("should report unhealthy for Supabase with null URL")
        void shouldReportUnhealthyForSupabaseWithNullUrl() {
            service.savePlatform(PlatformDbRequest.builder()
                    .id("supa_bad")
                    .name("Bad Supabase")
                    .type("supabase")
                    .active(true)
                    .supabase(PlatformDbRequest.SupabasePlatformConfig.builder()
                            .url(null)
                            .apiKey("key123")
                            .build())
                    .build());

            Map<String, Object> result = service.testPlatformConnection("supa_bad");
            assertEquals(false, result.get("healthy"));
        }

        @Test
        @DisplayName("should return empty for Supabase with null API key on fetch")
        void shouldReturnEmptyForSupabaseWithNullApiKey() {
            service.savePlatform(PlatformDbRequest.builder()
                    .id("supa_no_key")
                    .name("No Key Supabase")
                    .type("supabase")
                    .active(true)
                    .supabase(PlatformDbRequest.SupabasePlatformConfig.builder()
                            .url("https://xyz.supabase.co")
                            .apiKey(null)
                            .build())
                    .build());

            // Should return empty (catches the exception internally)
            List<UserResponse> users = service.getUsersFromPlatform("supa_no_key", null);
            assertTrue(users.isEmpty());
        }
    }
}
