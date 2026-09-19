package com.ayush.chat.storage.controller;

import com.ayush.chat.storage.dto.PlatformDbRequest;
import com.ayush.chat.storage.platform.PlatformUserService;
import com.ayush.chat.service.LoggerService;
import com.ayush.chat.saas.tenant.TenantRepository;
import com.ayush.chat.util.ThreadMemory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = PlatformController.class,
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class)
@TestPropertySource(properties = {
        "cors.allowed-origins=http://localhost:5173",
        "cors.allow-credentials=true"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
class PlatformControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PlatformUserService platformUserService;

    @MockBean
    private LoggerService loggerService;

    @MockBean
    private TenantRepository tenantRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private MockedStatic<ThreadMemory> threadMemoryMock;

    private PlatformDbRequest samplePlatform;

    @BeforeEach
    void setUp() {
        samplePlatform = PlatformDbRequest.builder()
                .id("test_platform")
                .name("Test Platform")
                .type("mysql")
                .active(true)
                .sql(PlatformDbRequest.SqlPlatformConfig.builder()
                        .url("jdbc:h2:mem:test")
                        .username("sa")
                        .password("")
                        .build())
                .build();

        threadMemoryMock = mockStatic(ThreadMemory.class);
        threadMemoryMock.when(ThreadMemory::getRequestLogger).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        if (threadMemoryMock != null) {
            threadMemoryMock.close();
        }
    }

    // ========== GET /api/admin/platforms ==========

    @Test
    @DisplayName("GET /api/admin/platforms - should return all platforms")
    void shouldReturnAllPlatforms() throws Exception {
        when(platformUserService.getAllPlatforms()).thenReturn(List.of(samplePlatform));

        mockMvc.perform(get("/api/admin/platforms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value("test_platform"))
                .andExpect(jsonPath("$[0].name").value("Test Platform"))
                .andExpect(jsonPath("$[0].type").value("mysql"));
    }

    @Test
    @DisplayName("GET /api/admin/platforms - should return empty list when no platforms")
    void shouldReturnEmptyList() throws Exception {
        when(platformUserService.getAllPlatforms()).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/platforms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ========== POST /api/admin/platforms ==========

    @Test
    @DisplayName("POST /api/admin/platforms - should save platform successfully")
    void shouldSavePlatform() throws Exception {
        doNothing().when(platformUserService).savePlatform(any(PlatformDbRequest.class));

        mockMvc.perform(post("/api/admin/platforms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(samplePlatform)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Platform saved: Test Platform"));
    }

    @Test
    @DisplayName("POST /api/admin/platforms - should return error on failure")
    void shouldReturnErrorOnSaveFailure() throws Exception {
        doThrow(new RuntimeException("Duplicate ID")).when(platformUserService).savePlatform(any());

        mockMvc.perform(post("/api/admin/platforms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(samplePlatform)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Failed to save: Duplicate ID"));
    }

    // ========== DELETE /api/admin/platforms/{id} ==========

    @Test
    @DisplayName("DELETE /api/admin/platforms/{id} - should remove existing platform")
    void shouldRemoveExistingPlatform() throws Exception {
        when(platformUserService.removePlatform("test_platform")).thenReturn(true);

        mockMvc.perform(delete("/api/admin/platforms/test_platform"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Platform removed"));
    }

    @Test
    @DisplayName("DELETE /api/admin/platforms/{id} - should return error for non-existent")
    void shouldReturnErrorForNonExistentPlatform() throws Exception {
        when(platformUserService.removePlatform("nonexistent")).thenReturn(false);

        mockMvc.perform(delete("/api/admin/platforms/nonexistent"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Platform not found"));
    }

    // ========== POST /api/admin/platforms/{id}/test ==========

    @Test
    @DisplayName("POST /api/admin/platforms/{id}/test - should test platform connection")
    void shouldTestPlatformConnection() throws Exception {
        when(platformUserService.testPlatformConnection("test_platform"))
                .thenReturn(Map.of("healthy", true, "message", "Connection successful"));

        mockMvc.perform(post("/api/admin/platforms/test_platform/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true))
                .andExpect(jsonPath("$.message").value("Connection successful"));
    }

    // ========== GET /api/admin/platforms/{id}/users ==========

    @Test
    @DisplayName("GET /api/admin/platforms/{id}/users - should fetch users")
    void shouldFetchUsersFromPlatform() throws Exception {
        var user = com.ayush.chat.storage.dto.UserResponse.builder()
                .id("u1")
                .email("alice@test.com")
                .fullName("Alice")
                .platformId("test_platform")
                .build();
        when(platformUserService.getUsersFromPlatform(eq("test_platform"), isNull()))
                .thenReturn(List.of(user));

        mockMvc.perform(get("/api/admin/platforms/test_platform/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].email").value("alice@test.com"));
    }

    @Test
    @DisplayName("GET /api/admin/platforms/{id}/users?q= - should pass query parameter")
    void shouldPassQueryParameter() throws Exception {
        when(platformUserService.getUsersFromPlatform("test_platform", "alice"))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/admin/platforms/test_platform/users").param("q", "alice"))
                .andExpect(status().isOk());

        verify(platformUserService).getUsersFromPlatform("test_platform", "alice");
    }

    @Test
    @DisplayName("GET /api/admin/platforms/{id}/users - should return 400 on error")
    void shouldReturn400OnFetchError() throws Exception {
        when(platformUserService.getUsersFromPlatform(eq("test_platform"), isNull()))
                .thenThrow(new IllegalArgumentException("Platform not found"));

        mockMvc.perform(get("/api/admin/platforms/test_platform/users"))
                .andExpect(status().isBadRequest());
    }

    // ========== GET /api/admin/users ==========

    @Test
    @DisplayName("GET /api/admin/users - should fetch all users")
    void shouldFetchAllUsers() throws Exception {
        when(platformUserService.getAllUsers(null)).thenReturn(List.of(
                com.ayush.chat.storage.dto.UserResponse.builder()
                        .id("u1").email("alice@test.com").fullName("Alice").build(),
                com.ayush.chat.storage.dto.UserResponse.builder()
                        .id("u2").email("bob@test.com").fullName("Bob").build()
        ));

        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("GET /api/admin/users?q= - should pass query parameter")
    void shouldPassQueryToGetAllUsers() throws Exception {
        when(platformUserService.getAllUsers("alice")).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/users").param("q", "alice"))
                .andExpect(status().isOk());

        verify(platformUserService).getAllUsers("alice");
    }
}
