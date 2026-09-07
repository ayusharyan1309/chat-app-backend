package com.ayush.chat.saas.config;

import com.ayush.chat.saas.tenant.TenantInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * WebMvc configuration for tenant interceptors.
 */
@Configuration
@RequiredArgsConstructor
public class TenantWebMvcConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/admin/**",  // Admin APIs don't need tenant resolution
                        "/api/public/**" // Public APIs don't need tenant resolution
                );
    }
}
