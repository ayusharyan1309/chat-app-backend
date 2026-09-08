package com.ayush.chat.storage.controller;

import com.ayush.chat.storage.dto.PlatformDbRequest;
import com.ayush.chat.storage.dto.UserResponse;
import com.ayush.chat.storage.platform.PlatformUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for platform database management and user fetching.
 * 
 * Endpoints:
 * - GET    /api/admin/platforms              — List all platform DBs
 * - POST   /api/admin/platforms              — Add/update a platform DB
 * - DELETE /api/admin/platforms/{id}         — Remove a platform DB
 * - POST   /api/admin/platforms/{id}/test    — Test platform connection
 * - GET    /api/admin/platforms/{id}/users   — Fetch users from a platform
 * - GET    /api/admin/users                  — Fetch all users from all platforms
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class PlatformController {

    private final PlatformUserService platformUserService;

    /**
     * Get all configured platform databases.
     */
    @GetMapping("/platforms")
    public ResponseEntity<List<PlatformDbRequest>> getPlatforms() {
        return ResponseEntity.ok(platformUserService.getAllPlatforms());
    }

    /**
     * Add or update a platform database configuration.
     */
    @PostMapping("/platforms")
    public ResponseEntity<Map<String, Object>> savePlatform(@RequestBody PlatformDbRequest request) {
        try {
            platformUserService.savePlatform(request);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Platform saved: " + request.getName()
            ));
        } catch (Exception e) {
            log.error("Failed to save platform", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Failed to save: " + e.getMessage()
            ));
        }
    }

    /**
     * Remove a platform database configuration.
     */
    @DeleteMapping("/platforms/{platformId}")
    public ResponseEntity<Map<String, Object>> removePlatform(@PathVariable String platformId) {
        boolean removed = platformUserService.removePlatform(platformId);
        if (removed) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Platform removed"
            ));
        }
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "Platform not found"
        ));
    }

    /**
     * Test connection to a specific platform database.
     */
    @PostMapping("/platforms/{platformId}/test")
    public ResponseEntity<Map<String, Object>> testPlatform(@PathVariable String platformId) {
        Map<String, Object> result = platformUserService.testPlatformConnection(platformId);
        return ResponseEntity.ok(result);
    }

    /**
     * Fetch users from a specific platform database.
     */
    @GetMapping("/platforms/{platformId}/users")
    public ResponseEntity<List<UserResponse>> getUsersFromPlatform(
            @PathVariable String platformId,
            @RequestParam(required = false) String q) {
        try {
            List<UserResponse> users = platformUserService.getUsersFromPlatform(platformId, q);
            return ResponseEntity.ok(users);
        } catch (Exception e) {
            log.error("Failed to fetch users from platform {}", platformId, e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Fetch all users from all configured platforms.
     */
    @GetMapping("/users")
    public ResponseEntity<List<UserResponse>> getAllUsers(@RequestParam(required = false) String q) {
        List<UserResponse> allUsers = platformUserService.getAllUsers(q);
        return ResponseEntity.ok(allUsers);
    }
}
