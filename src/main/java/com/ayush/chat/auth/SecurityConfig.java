package com.ayush.chat.auth;

import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.core.error.ErrorCode;
import com.ayush.chat.core.error.ErrorHttpMapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] ADMIN_ROLES = {"OWNER", "ADMIN", "VIEWER"};
    private static final String[] ADMIN_WRITE_ROLES = {"OWNER", "ADMIN"};

    /** Disables Spring Boot's generated in-memory user; all authentication is token based. */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException(username);
        };
    }

    /** The auth filter runs inside the security chain only, not as a second servlet filter. */
    @Bean
    public FilterRegistrationBean<PlatformAuthFilter> platformAuthFilterRegistration(PlatformAuthFilter filter) {
        FilterRegistrationBean<PlatformAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, PlatformAuthFilter authFilter,
                                                   ObjectMapper mapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> {
                })
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.frameOptions(f -> f.sameOrigin()))
                .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeError(mapper, req, res, ErrorCode.UNAUTHORIZED,
                                "Authentication required"))
                        .accessDeniedHandler((req, res, ex) -> writeError(mapper, req, res, ErrorCode.FORBIDDEN,
                                "You do not have access to this resource")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/auth/login").permitAll()
                        .requestMatchers("/api/admin/auth/me", "/api/admin/auth/change-password").hasAnyRole(ADMIN_ROLES)
                        .requestMatchers(HttpMethod.GET, "/api/admin/**").hasAnyRole(ADMIN_ROLES)
                        .requestMatchers("/api/admin/**").hasAnyRole(ADMIN_WRITE_ROLES)
                        .requestMatchers("/api/v1/auth/**", "/api/v1/tenants/*/config").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/media/*/*").permitAll()
                        .requestMatchers("/api/v1/server/**").hasRole("TENANT_SERVER")
                        .requestMatchers("/api/v1/**").hasRole("CHAT_USER")
                        .requestMatchers("/ws/**", "/ws-sockjs/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasAnyRole(ADMIN_ROLES)
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/**").denyAll()
                        .anyRequest().permitAll());
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(PlatformProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(properties.getCorsAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-API-Key", "X-Requested-With"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private static void writeError(ObjectMapper mapper, HttpServletRequest req, HttpServletResponse res, ErrorCode fallback,
                                   String fallbackMessage) throws IOException {
        ErrorCode code = fallback;
        String message = fallbackMessage;
        if (req.getAttribute(PlatformAuthFilter.AUTH_ERROR_ATTRIBUTE) instanceof DomainException authError) {
            code = authError.getErrorCode();
            message = authError.getMessage();
        }
        res.setStatus(ErrorHttpMapping.status(code).value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), ErrorHttpMapping.body(code, message, req.getRequestURI()));
    }
}
