package com.ayush.chat.storage.config;

import com.ayush.chat.storage.ChatStorageFactory;
import com.ayush.chat.storage.ChatStorageProvider;
import com.ayush.chat.storage.StorageType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the Chat Storage abstraction layer.
 * 
 * Automatically creates the correct ChatStorageProvider based on the
 * configured storage type in application.yml.
 * 
 * <h3>How it works:</h3>
 * <ol>
 *   <li>Reads {@code chat.storage.type} from application.yml</li>
 *   <li>Creates a {@link ChatStorageFactory} with the configuration</li>
 *   <li>Creates the appropriate {@link ChatStorageProvider} based on the type</li>
 *   <li>The provider is injected into any service that needs it</li>
 * </ol>
 * 
 * <h3>Switching databases:</h3>
 * Just change one line in application.yml:
 * <pre>
 * chat:
 *   storage:
 *     type: mongodb   # Change to: mysql, postgresql, mongodb, firebase, supabase, h2
 * </pre>
 * No code changes needed!
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(ChatStorageProperties.class)
public class ChatStorageAutoConfiguration {

    /**
     * Create the ChatStorageFactory.
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatStorageFactory chatStorageFactory(ChatStorageProperties properties) {
        log.info("========================================");
        log.info("Chat Storage Auto-Configuration Starting");
        log.info("========================================");
        log.info("Configured storage type: {} ({})", 
                properties.getType().getDisplayName(), properties.getType().getCode());
        log.info("Collection prefix: {}", properties.getCollectionPrefix());
        log.info("Audit logging: {}", properties.isEnableAudit() ? "enabled" : "disabled");
        log.info("========================================");

        return new ChatStorageFactory(properties);
    }

    /**
     * Create the active ChatStorageProvider.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ChatStorageFactory.class)
    public ChatStorageProvider chatStorageProvider(ChatStorageFactory factory) {
        ChatStorageProvider provider = factory.getProvider();

        log.info("========================================");
        log.info("Chat Storage Provider Activated:");
        log.info("  Type: {}", provider.getStorageType().getDisplayName());
        log.info("  Description: {}", provider.getDescription());

        if (provider.isHealthy()) {
            log.info("  Status: HEALTHY");
        } else {
            log.warn("  Status: UNHEALTHY - The storage backend may not be available");
        }

        log.info("========================================");

        return provider;
    }
}
