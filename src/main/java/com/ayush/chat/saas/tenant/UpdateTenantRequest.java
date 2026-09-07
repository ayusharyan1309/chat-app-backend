package com.ayush.chat.saas.tenant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for updating an existing tenant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateTenantRequest {

    private String displayName;
    private String logoUrl;
    private String primaryColor;
    private String secondaryColor;
    
    // Limits
    private Integer maxUsers;
    private Integer maxStorageGb;
    private Long maxMessagesPerDay;
    
    // Plan
    private String planType;
}
