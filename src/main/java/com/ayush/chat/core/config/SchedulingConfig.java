package com.ayush.chat.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The application scheduler for @Scheduled jobs and config polling. Declared explicitly because
 * the WebSocket broker registers its own scheduler, which would otherwise be picked up.
 */
@Configuration
public class SchedulingConfig {

    @Bean(name = "taskScheduler", destroyMethod = "shutdown")
    @Primary
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(8);
        scheduler.setThreadNamePrefix("platform-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }
}
