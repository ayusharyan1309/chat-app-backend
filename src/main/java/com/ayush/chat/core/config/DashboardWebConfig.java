package com.ayush.chat.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Serves the bundled admin dashboard (built into {@code classpath:/static}) from the same origin
 * as the API. Unknown non-API paths fall back to {@code index.html} so client-side routes such as
 * {@code /tenants/acme} survive a page reload.
 */
@Configuration
public class DashboardWebConfig implements WebMvcConfigurer {

    private static final String[] RESERVED = {"api/", "ws", "ws-sockjs", "actuator", "swagger-ui", "v3/api-docs"};

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        Resource requested = location.createRelative(path);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        for (String prefix : RESERVED) {
                            if (path.startsWith(prefix)) {
                                return null;
                            }
                        }
                        Resource index = new ClassPathResource("/static/index.html");
                        return index.exists() ? index : null;
                    }
                });
    }
}
