package com.ayush.chat.saas.tenant;

import com.ayush.chat.saas.config.DatabaseType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a new tenant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateTenantRequest {

    private String tenantName;
    private String displayName;
    private String domain;
    private String contactEmail;
    private String contactPhone;
    private DatabaseType databaseType;
    
    // External database configuration (optional)
    private String externalDatabaseUrl;
    private String externalDbUsername;
    private String externalDbPassword;
    
    // Limits
    private Integer maxUsers;
    private Integer maxStorageGb;
    private Long maxMessagesPerDay;
    
    // Plan
    private String planType;
}
