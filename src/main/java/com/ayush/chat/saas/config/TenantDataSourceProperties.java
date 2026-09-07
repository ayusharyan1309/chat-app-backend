package com.ayush.chat.saas.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Properties for a tenant's database connection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantDataSourceProperties {

    private String tenantId;
    private String tenantName;
    private DatabaseType databaseType;
    private String host;
    private int port;
    private String databaseName;
    private String username;
    private String password;
    
    // Connection pool settings
    @Builder.Default
    private int maxPoolSize = 10;
    
    @Builder.Default
    private int minIdle = 2;
    
    @Builder.Default
    private long connectionTimeout = 30000;
    
    @Builder.Default
    private long idleTimeout = 600000;
    
    @Builder.Default
    private long maxLifetime = 1800000;

    /**
     * Generates the JDBC URL based on database type and connection details.
     */
    public String generateJdbcUrl() {
        return databaseType.generateUrl(host, port, databaseName);
    }

    /**
     * Generates a Hikari pool name for this tenant.
     */
    public String getPoolName() {
        return "pool-tenant-" + tenantId;
    }
}
