package com.ayush.chat.saas.config;

import com.ayush.chat.saas.tenant.TenantContext;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic data source configuration that supports multiple database types
 * and dynamic tenant-specific database connections.
 * 
 * Supported databases:
 * - MySQL
 * - PostgreSQL
 * - H2 (for development/testing)
 * - MongoDB (via separate configuration)
 */
@Slf4j
@Configuration
public class DynamicDataSourceConfig {

    private final Map<String, DataSource> tenantDataSources = new ConcurrentHashMap<>();
    private DataSource defaultDataSource;

    /**
     * Creates the default data source for the master/admin database.
     */
    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource.master")
    @ConditionalOnMissingBean(name = "masterDataSource")
    public DataSource masterDataSource() {
        this.defaultDataSource = DataSourceBuilder.create().build();
        return this.defaultDataSource;
    }

    /**
     * Gets the data source for a specific tenant.
     * Creates a new data source if it doesn't exist.
     */
    public DataSource getDataSource(String tenantIdentifier) {
        return tenantDataSources.computeIfAbsent(tenantIdentifier, this::createTenantDataSource);
    }

    /**
     * Gets the default data source.
     */
    public DataSource getDefaultDataSource() {
        if (defaultDataSource == null) {
            defaultDataSource = masterDataSource();
        }
        return defaultDataSource;
    }

    /**
     * Creates a new data source for a tenant based on configuration.
     */
    private DataSource createTenantDataSource(String tenantIdentifier) {
        log.info("Creating data source for tenant: {}", tenantIdentifier);
        
        // This will be populated from tenant configuration stored in master DB
        HikariConfig config = new HikariConfig();
        config.setPoolName("tenant-" + tenantIdentifier);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        
        // Default to H2 for new tenants
        config.setDriverClassName("org.h2.Driver");
        config.setJdbcUrl("jdbc:h2:mem:tenant_" + tenantIdentifier + ";DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        
        return new HikariDataSource(config);
    }

    /**
     * Removes a tenant's data source from cache (useful for tenant deletion).
     */
    public void removeTenantDataSource(String tenantIdentifier) {
        DataSource dataSource = tenantDataSources.remove(tenantIdentifier);
        if (dataSource != null) {
            try {
                dataSource.close();
            } catch (Exception e) {
                log.error("Error closing data source for tenant: {}", tenantIdentifier, e);
            }
        }
    }

    /**
     * Refreshes a tenant's data source (useful for configuration changes).
     */
    public void refreshTenantDataSource(String tenantIdentifier) {
        removeTenantDataSource(tenantIdentifier);
        getDataSource(tenantIdentifier);
    }
}
