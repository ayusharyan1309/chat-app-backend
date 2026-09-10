package com.ayush.chat.storage.controller;

import com.ayush.chat.storage.ChatStorageFactory;
import com.ayush.chat.storage.ChatStorageProvider;
import com.ayush.chat.storage.StorageType;
import com.ayush.chat.storage.config.ChatStorageProperties;
import com.ayush.chat.storage.dto.StorageConfigRequest;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = StorageConfigController.class,
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class)
@TestPropertySource(properties = {
        "cors.allowed-origins=http://localhost:5173",
        "cors.allow-credentials=true"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
class StorageConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatStorageProperties properties;

    @MockBean
    private ChatStorageFactory storageFactory;

    @MockBean
    private LoggerService loggerService;

    @MockBean
    private TenantRepository tenantRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private ChatStorageProvider mockProvider;
    private MockedStatic<ThreadMemory> threadMemoryMock;

    @BeforeEach
    void setUp() {
        mockProvider = mock(ChatStorageProvider.class);

        // Default: H2 config
        when(properties.getType()).thenReturn(StorageType.H2);
        when(properties.getSql()).thenReturn(ChatStorageProperties.SqlConfig.builder()
                .url("jdbc:h2:mem:testdb")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .poolSize(10)
                .build());
        when(properties.getMongodb()).thenReturn(ChatStorageProperties.MongoDbConfig.builder().build());
        when(properties.getFirebase()).thenReturn(ChatStorageProperties.FirebaseConfig.builder().build());
        when(properties.getSupabase()).thenReturn(ChatStorageProperties.SupabaseConfig.builder().build());

        when(storageFactory.getProvider()).thenReturn(mockProvider);

        threadMemoryMock = mockStatic(ThreadMemory.class);
        threadMemoryMock.when(ThreadMemory::getRequestLogger).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        if (threadMemoryMock != null) {
            threadMemoryMock.close();
        }
    }

    // ========== GET /api/admin/storage/config ==========

    @Test
    @DisplayName("GET /config - should return SQL config for H2 type")
    void shouldReturnSqlConfigForH2() throws Exception {
        mockMvc.perform(get("/api/admin/storage/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("h2"))
                .andExpect(jsonPath("$.sql.url").value("jdbc:h2:mem:testdb"))
                .andExpect(jsonPath("$.sql.username").value("sa"));
    }

    @Test
    @DisplayName("GET /config - should return SQL config for MySQL type")
    void shouldReturnSqlConfigForMySQL() throws Exception {
        when(properties.getType()).thenReturn(StorageType.MYSQL);
        when(properties.getSql()).thenReturn(ChatStorageProperties.SqlConfig.builder()
                .url("jdbc:mysql://localhost:3306/chat")
                .username("root")
                .password("pass")
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .poolSize(20)
                .build());

        mockMvc.perform(get("/api/admin/storage/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("mysql"))
                .andExpect(jsonPath("$.sql.url").value("jdbc:mysql://localhost:3306/chat"));
    }

    @Test
    @DisplayName("GET /config - should return SQL config for PostgreSQL type")
    void shouldReturnSqlConfigForPostgreSQL() throws Exception {
        when(properties.getType()).thenReturn(StorageType.POSTGRESQL);
        when(properties.getSql()).thenReturn(ChatStorageProperties.SqlConfig.builder()
                .url("jdbc:postgresql://localhost:5432/chat")
                .username("postgres")
                .password("pass")
                .build());

        mockMvc.perform(get("/api/admin/storage/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("postgresql"));
    }

    @Test
    @DisplayName("GET /config - should return MongoDB config")
    void shouldReturnMongoDbConfig() throws Exception {
        when(properties.getType()).thenReturn(StorageType.MONGODB);
        when(properties.getMongodb()).thenReturn(ChatStorageProperties.MongoDbConfig.builder()
                .uri("mongodb://localhost:27017")
                .host("localhost")
                .port(27017)
                .database("chatdb")
                .username("admin")
                .password("secret")
                .build());

        mockMvc.perform(get("/api/admin/storage/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("mongodb"))
                .andExpect(jsonPath("$.mongodb.uri").value("mongodb://localhost:27017"))
                .andExpect(jsonPath("$.mongodb.database").value("chatdb"));
    }

    @Test
    @DisplayName("GET /config - should return Firebase config")
    void shouldReturnFirebaseConfig() throws Exception {
        when(properties.getType()).thenReturn(StorageType.FIREBASE);
        when(properties.getFirebase()).thenReturn(ChatStorageProperties.FirebaseConfig.builder()
                .projectId("my-firebase-project")
                .credentialPath("/path/to/cred.json")
                .firestoreDatabase("(default)")
                .build());

        mockMvc.perform(get("/api/admin/storage/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("firebase"))
                .andExpect(jsonPath("$.firebase.projectId").value("my-firebase-project"));
    }

    @Test
    @DisplayName("GET /config - should return Supabase config")
    void shouldReturnSupabaseConfig() throws Exception {
        when(properties.getType()).thenReturn(StorageType.SUPABASE);
        when(properties.getSupabase()).thenReturn(ChatStorageProperties.SupabaseConfig.builder()
                .url("https://xyz.supabase.co")
                .apiKey("key123")
                .anonKey("anon456")
                .schema("public")
                .build());

        mockMvc.perform(get("/api/admin/storage/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("supabase"))
                .andExpect(jsonPath("$.supabase.url").value("https://xyz.supabase.co"))
                .andExpect(jsonPath("$.supabase.apiKey").value("key123"));
    }

    // ========== PUT /api/admin/storage/config ==========

    @Test
    @DisplayName("PUT /config - should update to MySQL config")
    void shouldUpdateToMySqlConfig() throws Exception {
        StorageConfigRequest request = StorageConfigRequest.builder()
                .type("mysql")
                .sql(StorageConfigRequest.SqlConfigRequest.builder()
                        .url("jdbc:mysql://localhost:3306/newdb")
                        .username("root")
                        .password("newpass")
                        .build())
                .build();

        mockMvc.perform(put("/api/admin/storage/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Storage configuration updated to MySQL"));

        verify(properties).setType(StorageType.MYSQL);
        verify(storageFactory).refreshProvider(StorageType.MYSQL);
    }

    @Test
    @DisplayName("PUT /config - should update to MongoDB config")
    void shouldUpdateToMongoDbConfig() throws Exception {
        StorageConfigRequest request = StorageConfigRequest.builder()
                .type("mongodb")
                .mongodb(StorageConfigRequest.MongoDbConfigRequest.builder()
                        .uri("mongodb://newhost:27017")
                        .database("newdb")
                        .build())
                .build();

        mockMvc.perform(put("/api/admin/storage/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(properties).setType(StorageType.MONGODB);
        verify(storageFactory).refreshProvider(StorageType.MONGODB);
    }

    @Test
    @DisplayName("PUT /config - should update to Firebase config")
    void shouldUpdateToFirebaseConfig() throws Exception {
        StorageConfigRequest request = StorageConfigRequest.builder()
                .type("firebase")
                .firebase(StorageConfigRequest.FirebaseConfigRequest.builder()
                        .projectId("new-project")
                        .build())
                .build();

        mockMvc.perform(put("/api/admin/storage/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(properties).setType(StorageType.FIREBASE);
        verify(storageFactory).refreshProvider(StorageType.FIREBASE);
    }

    @Test
    @DisplayName("PUT /config - should update to Supabase config")
    void shouldUpdateToSupabaseConfig() throws Exception {
        StorageConfigRequest request = StorageConfigRequest.builder()
                .type("supabase")
                .supabase(StorageConfigRequest.SupabaseConfigRequest.builder()
                        .url("https://new.supabase.co")
                        .apiKey("newkey")
                        .build())
                .build();

        mockMvc.perform(put("/api/admin/storage/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(properties).setType(StorageType.SUPABASE);
        verify(storageFactory).refreshProvider(StorageType.SUPABASE);
    }

    @Test
    @DisplayName("PUT /config - should return error for invalid type")
    void shouldReturnErrorForInvalidType() throws Exception {
        StorageConfigRequest request = StorageConfigRequest.builder()
                .type("invalid_type")
                .build();

        mockMvc.perform(put("/api/admin/storage/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ========== POST /api/admin/storage/test ==========

    @Test
    @DisplayName("POST /test - should return success when healthy")
    void shouldReturnSuccessWhenHealthy() throws Exception {
        when(mockProvider.isHealthy()).thenReturn(true);
        when(mockProvider.getStorageType()).thenReturn(StorageType.H2);

        mockMvc.perform(post("/api/admin/storage/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true))
                .andExpect(jsonPath("$.type").value("H2 Database"))
                .andExpect(jsonPath("$.message").value("Connection successful"));
    }

    @Test
    @DisplayName("POST /test - should return failure when unhealthy")
    void shouldReturnFailureWhenUnhealthy() throws Exception {
        when(mockProvider.isHealthy()).thenReturn(false);
        when(mockProvider.getStorageType()).thenReturn(StorageType.H2);

        mockMvc.perform(post("/api/admin/storage/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(false))
                .andExpect(jsonPath("$.message").value("Connection unhealthy"));
    }

    @Test
    @DisplayName("POST /test - should handle provider exception")
    void shouldHandleProviderException() throws Exception {
        when(storageFactory.getProvider()).thenThrow(new RuntimeException("Provider init failed"));

        mockMvc.perform(post("/api/admin/storage/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(false))
                .andExpect(jsonPath("$.message").value("Connection failed: Provider init failed"));
    }

    // ========== GET /api/admin/storage/types ==========

    @Test
    @DisplayName("GET /types - should return available storage types")
    void shouldReturnAvailableTypes() throws Exception {
        mockMvc.perform(get("/api/admin/storage/types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(6));
    }

    @Test
    @DisplayName("GET /types - should include all chat-supported types")
    void shouldIncludeAllChatSupportedTypes() throws Exception {
        mockMvc.perform(get("/api/admin/storage/types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").isArray());
    }
}
