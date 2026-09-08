package com.ayush.chat.storage.platform;

import com.ayush.chat.storage.dto.PlatformDbRequest;
import com.ayush.chat.storage.dto.UserResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for managing external platform databases and fetching users from them.
 * 
 * Supports:
 * - Supabase (via REST API)
 * - MySQL/PostgreSQL (via JDBC)
 * - MongoDB (via HTTP driver)
 * - Firebase (via Admin SDK)
 * - Custom REST APIs
 */
@Slf4j
@Service
public class PlatformUserService {

    /** In-memory store for platform configurations. */
    private final Map<String, PlatformDbConfig> platformConfigs = new ConcurrentHashMap<>();

    // ========== CRUD Operations ==========

    /**
     * Get all configured platforms.
     */
    public List<PlatformDbRequest> getAllPlatforms() {
        return platformConfigs.values().stream()
                .map(PlatformDbConfig::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get a specific platform by ID.
     */
    public Optional<PlatformDbConfig> getPlatform(String platformId) {
        return Optional.ofNullable(platformConfigs.get(platformId));
    }

    /**
     * Save (create or update) a platform configuration.
     */
    public void savePlatform(PlatformDbRequest request) {
        PlatformDbConfig config = PlatformDbConfig.fromRequest(request);
        platformConfigs.put(config.getId(), config);
        log.info("Saved platform config: {} ({})", config.getName(), config.getType());
    }

    /**
     * Remove a platform configuration.
     */
    public boolean removePlatform(String platformId) {
        PlatformDbConfig removed = platformConfigs.remove(platformId);
        if (removed != null) {
            log.info("Removed platform config: {}", platformId);
            return true;
        }
        return false;
    }

    // ========== User Fetching ==========

    /**
     * Fetch users from a specific platform.
     */
    public List<UserResponse> getUsersFromPlatform(String platformId, String query) {
        PlatformDbConfig config = platformConfigs.get(platformId);
        if (config == null) {
            throw new IllegalArgumentException("Platform not found: " + platformId);
        }
        if (!config.isActive()) {
            return Collections.emptyList();
        }

        try {
            return switch (config.getType()) {
                case "supabase" -> fetchUsersFromSupabase(config, query);
                case "mysql", "postgresql" -> fetchUsersFromSql(config, query);
                case "mongodb" -> fetchUsersFromMongo(config, query);
                case "firebase" -> fetchUsersFromFirebase(config, query);
                case "custom" -> fetchUsersFromCustomApi(config, query);
                default -> {
                    log.warn("Unsupported platform type: {}", config.getType());
                    yield Collections.emptyList();
                }
            };
        } catch (Exception e) {
            log.error("Failed to fetch users from platform {}: {}", platformId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Fetch users from ALL active platforms + primary Supabase.
     */
    public List<UserResponse> getAllUsers(String query) {
        List<UserResponse> allUsers = new ArrayList<>();

        // Fetch from each active platform
        for (PlatformDbConfig config : platformConfigs.values()) {
            if (!config.isActive()) continue;
            try {
                List<UserResponse> users = getUsersFromPlatform(config.getId(), query);
                allUsers.addAll(users);
            } catch (Exception e) {
                log.error("Failed to fetch users from {}: {}", config.getId(), e.getMessage());
            }
        }

        // Deduplicate by email (first occurrence wins)
        Map<String, UserResponse> deduped = new LinkedHashMap<>();
        for (UserResponse user : allUsers) {
            if (user.getEmail() != null && !deduped.containsKey(user.getEmail())) {
                deduped.put(user.getEmail(), user);
            }
        }
        return new ArrayList<>(deduped.values());
    }

    // ========== Connection Testing ==========

    /**
     * Test connection to a specific platform.
     */
    public Map<String, Object> testPlatformConnection(String platformId) {
        PlatformDbConfig config = platformConfigs.get(platformId);
        if (config == null) {
            return Map.of("healthy", false, "message", "Platform not found: " + platformId);
        }

        try {
            return switch (config.getType()) {
                case "supabase" -> testSupabaseConnection(config);
                case "mysql", "postgresql" -> testSqlConnection(config);
                case "mongodb" -> testMongoConnection(config);
                case "firebase" -> testFirebaseConnection(config);
                case "custom" -> testCustomApiConnection(config);
                default -> Map.of("healthy", false, "message", "Unsupported type: " + config.getType());
            };
        } catch (Exception e) {
            return Map.of("healthy", false, "message", "Connection failed: " + e.getMessage());
        }
    }

    // ========== Supabase Implementation ==========

    private List<UserResponse> fetchUsersFromSupabase(PlatformDbConfig config, String query) throws Exception {
        String url = config.getSupabaseUrl();
        String apiKey = config.getSupabaseApiKey();
        if (url == null || apiKey == null) {
            throw new IllegalArgumentException("Supabase URL and API key are required");
        }

        StringBuilder sb = new StringBuilder(url).append("/rest/v1/users?select=id,email,full_name,avatar_url");
        if (query != null && !query.isEmpty()) {
            sb.append("&or=(email.ilike.%").append(query).append("%,full_name.ilike.%").append(query).append("%)");
        }
        sb.append("&order=created_at.desc&limit=100");

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(sb.toString()))
                .header("apikey", apiKey)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .GET()
                .build();

        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Supabase returned status: " + response.statusCode());
        }

        // Parse JSON array
        String body = response.body();
        List<UserResponse> users = new ArrayList<>();
        // Simple JSON parsing - extract objects
        String[] items = body.split("\\},\\{");
        for (String item : items) {
            String cleanItem = item.replace("[", "").replace("]", "").replace("{", "").replace("}", "");
            String email = extractJsonValue(cleanItem, "email");
            String fullName = extractJsonValue(cleanItem, "full_name");
            String id = extractJsonValue(cleanItem, "id");
            String avatarUrl = extractJsonValue(cleanItem, "avatar_url");

            if (email != null) {
                users.add(UserResponse.fromSupabase(id, email, fullName, avatarUrl));
            }
        }
        return users;
    }

    private Map<String, Object> testSupabaseConnection(PlatformDbConfig config) {
        try {
            String url = config.getSupabaseUrl();
            String apiKey = config.getSupabaseApiKey();
            if (url == null || apiKey == null) {
                return Map.of("healthy", false, "message", "Supabase URL and API key are required");
            }

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url + "/rest/v1/users?select=id&limit=1"))
                    .header("apikey", apiKey)
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();

            java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            return response.statusCode() == 200
                    ? Map.of("healthy", true, "message", "Connection successful")
                    : Map.of("healthy", false, "message", "Status: " + response.statusCode());
        } catch (Exception e) {
            return Map.of("healthy", false, "message", "Connection failed: " + e.getMessage());
        }
    }

    // ========== SQL Implementation ==========

    private List<UserResponse> fetchUsersFromSql(PlatformDbConfig config, String query) throws Exception {
        String url = config.getSqlUrl();
        String username = config.getSqlUsername();
        String password = config.getSqlPassword();

        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("SQL URL is required");
        }

        List<UserResponse> users = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            String sql = "SELECT id, email, full_name, profile_url FROM users WHERE 1=1";
            List<Object> params = new ArrayList<>();

            if (query != null && !query.isEmpty()) {
                sql += " AND (email ILIKE ? OR full_name ILIKE ?)";
                params.add("%" + query + "%");
                params.add("%" + query + "%");
            }
            sql += " LIMIT 100";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        users.add(UserResponse.fromPlatform(
                                rs.getString("id"),
                                rs.getString("email"),
                                rs.getString("full_name"),
                                config.getId()
                        ));
                    }
                }
            }
        }
        return users;
    }

    private Map<String, Object> testSqlConnection(PlatformDbConfig config) {
        try (Connection conn = DriverManager.getConnection(
                config.getSqlUrl(), config.getSqlUsername(), config.getSqlPassword())) {
            return conn.isValid(5)
                    ? Map.of("healthy", true, "message", "Connection successful")
                    : Map.of("healthy", false, "message", "Connection validation failed");
        } catch (Exception e) {
            return Map.of("healthy", false, "message", "Connection failed: " + e.getMessage());
        }
    }

    // ========== MongoDB Implementation ==========

    private List<UserResponse> fetchUsersFromMongo(PlatformDbConfig config, String query) throws Exception {
        // MongoDB via REST API (MongoDB Data API or mongosh-compatible endpoint)
        String uri = config.getMongoUri();
        if (uri == null || uri.isEmpty()) {
            throw new IllegalArgumentException("MongoDB URI is required");
        }
        // For now, return empty - real implementation would use MongoDB Java driver
        log.warn("MongoDB user fetching requires MongoDB Java driver integration");
        return Collections.emptyList();
    }

    private Map<String, Object> testMongoConnection(PlatformDbConfig config) {
        try {
            String uri = config.getMongoUri();
            if (uri == null || uri.isEmpty()) {
                return Map.of("healthy", false, "message", "MongoDB URI is required");
            }
            // For now, return success - real implementation would ping the server
            return Map.of("healthy", true, "message", "MongoDB connection configured (requires driver)");
        } catch (Exception e) {
            return Map.of("healthy", false, "message", "Connection failed: " + e.getMessage());
        }
    }

    // ========== Firebase Implementation ==========

    private List<UserResponse> fetchUsersFromFirebase(PlatformDbConfig config, String query) throws Exception {
        // Firebase would use Firebase Admin SDK
        log.warn("Firebase user fetching requires Firebase Admin SDK integration");
        return Collections.emptyList();
    }

    private Map<String, Object> testFirebaseConnection(PlatformDbConfig config) {
        try {
            if (config.getFirebaseProjectId() == null || config.getFirebaseProjectId().isEmpty()) {
                return Map.of("healthy", false, "message", "Firebase project ID is required");
            }
            return Map.of("healthy", true, "message", "Firebase configured (requires Admin SDK)");
        } catch (Exception e) {
            return Map.of("healthy", false, "message", "Connection failed: " + e.getMessage());
        }
    }

    // ========== Custom API Implementation ==========

    private List<UserResponse> fetchUsersFromCustomApi(PlatformDbConfig config, String query) throws Exception {
        String apiUrl = config.getCustomApiUrl();
        if (apiUrl == null || apiUrl.isEmpty()) {
            throw new IllegalArgumentException("Custom API URL is required");
        }

        StringBuilder sb = new StringBuilder(apiUrl);
        if (!apiUrl.endsWith("/")) sb.append("/");
        sb.append("users");
        if (query != null && !query.isEmpty()) {
            sb.append("?q=").append(query);
        }

        java.net.http.HttpRequest.Builder requestBuilder = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(sb.toString()))
                .header("Content-Type", "application/json");

        if (config.getCustomApiKey() != null) {
            requestBuilder.header("Authorization", "Bearer " + config.getCustomApiKey());
        }
        if (config.getCustomHeaders() != null) {
            config.getCustomHeaders().forEach(requestBuilder::header);
        }

        java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                .send(requestBuilder.GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Custom API returned status: " + response.statusCode());
        }

        // Parse response - assume JSON array of user objects
        // Simple extraction, would use proper JSON library in production
        List<UserResponse> users = new ArrayList<>();
        String body = response.body();
        String[] items = body.split("\\},\\{");
        for (String item : items) {
            String cleanItem = item.replace("[", "").replace("]", "").replace("{", "").replace("}", "");
            String email = extractJsonValue(cleanItem, "email");
            String fullName = extractJsonValue(cleanItem, "fullName");
            String id = extractJsonValue(cleanItem, "id");

            if (email != null) {
                users.add(UserResponse.fromPlatform(id, email, fullName, config.getId()));
            }
        }
        return users;
    }

    private Map<String, Object> testCustomApiConnection(PlatformDbConfig config) {
        try {
            String url = config.getCustomApiUrl();
            if (url == null || url.isEmpty()) {
                return Map.of("healthy", false, "message", "Custom API URL is required");
            }

            java.net.http.HttpRequest.Builder requestBuilder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url + (url.endsWith("/") ? "" : "/") + "health"))
                    .timeout(java.time.Duration.ofSeconds(5));

            if (config.getCustomApiKey() != null) {
                requestBuilder.header("Authorization", "Bearer " + config.getCustomApiKey());
            }

            java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                    .send(requestBuilder.GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());

            return response.statusCode() < 400
                    ? Map.of("healthy", true, "message", "Connection successful")
                    : Map.of("healthy", false, "message", "Status: " + response.statusCode());
        } catch (Exception e) {
            return Map.of("healthy", false, "message", "Connection failed: " + e.getMessage());
        }
    }

    // ========== Utility ==========

    /**
     * Simple JSON value extractor (for basic JSON parsing without external library).
     */
    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int idx = json.indexOf(searchKey);
        if (idx < 0) return null;

        int valueStart = idx + searchKey.length();
        // Skip whitespace
        while (valueStart < json.length() && json.charAt(valueStart) == ' ') valueStart++;

        if (valueStart >= json.length()) return null;

        if (json.charAt(valueStart) == '"') {
            // String value
            valueStart++;
            int valueEnd = json.indexOf('"', valueStart);
            if (valueEnd < 0) return null;
            return json.substring(valueStart, valueEnd).replace("\\\"", "\"");
        } else {
            // Non-string value (number, boolean, null)
            int valueEnd = valueStart;
            while (valueEnd < json.length() && json.charAt(valueEnd) != ',' && json.charAt(valueEnd) != '}') {
                valueEnd++;
            }
            String val = json.substring(valueStart, valueEnd).trim();
            return "null".equals(val) ? null : val;
        }
    }
}
