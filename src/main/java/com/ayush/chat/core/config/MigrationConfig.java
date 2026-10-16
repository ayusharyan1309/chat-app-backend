package com.ayush.chat.core.config;

import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Control-plane migrations that tolerate several nodes starting at once against a fresh
 * database: Liquibase's lock table cannot protect its own creation, so a losing node retries.
 */
@Configuration
@EnableConfigurationProperties(LiquibaseProperties.class)
public class MigrationConfig {

    @Bean
    public SpringLiquibase liquibase(DataSource dataSource, LiquibaseProperties properties) {
        SpringLiquibase liquibase = new RetryingLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(properties.getChangeLog());
        liquibase.setShouldRun(properties.isEnabled());
        liquibase.setContexts(properties.getContexts() == null ? null : String.join(",", properties.getContexts()));
        liquibase.setDefaultSchema(properties.getDefaultSchema());
        return liquibase;
    }

    static class RetryingLiquibase extends SpringLiquibase {

        private static final int ATTEMPTS = 6;
        /** Not {@code log}: SpringLiquibase already has a field with that name. */
        private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(RetryingLiquibase.class);

        @Override
        public void afterPropertiesSet() throws LiquibaseException {
            for (int attempt = 1; ; attempt++) {
                try {
                    super.afterPropertiesSet();
                    return;
                } catch (LiquibaseException | RuntimeException e) {
                    if (attempt >= ATTEMPTS) {
                        throw e;
                    }
                    long backoff = 1000L * attempt + ThreadLocalRandom.current().nextLong(1000);
                    LOGGER.warn("Migration attempt {} failed ({}); another node is probably migrating. Retrying in {} ms",
                            attempt, e.getMessage(), backoff);
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
    }
}
