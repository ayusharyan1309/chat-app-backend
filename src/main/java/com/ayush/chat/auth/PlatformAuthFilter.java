package com.ayush.chat.auth;

import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.tenant.TenantDirectory;
import com.ayush.chat.tenant.UsageMeter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the caller from {@code X-API-Key} or {@code Authorization: Bearer}. Invalid
 * credentials leave the request unauthenticated (protected routes then answer 401 with the reason);
 * public routes keep working.
 */
@Component
@RequiredArgsConstructor
public class PlatformAuthFilter extends OncePerRequestFilter {

    public static final String AUTH_ERROR_ATTRIBUTE = "platform.authError";
    static final String API_KEY_HEADER = "X-API-Key";
    private static final String BEARER = "Bearer ";

    private final JwtService jwt;
    private final ApiKeyService apiKeys;
    private final AdminDirectory admins;
    private final ActiveUserCheck users;
    private final TenantDirectory tenants;
    private final UsageMeter usage;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            authenticate(request).ifPresent(auth -> SecurityContextHolder.getContext().setAuthentication(auth));
        } catch (DomainException e) {
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, e);
        }
        chain.doFilter(request, response);
    }

    private Optional<UsernamePasswordAuthenticationToken> authenticate(HttpServletRequest request) {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            Principals.TenantServerPrincipal server = apiKeys.authenticate(apiKey.trim())
                    .orElseThrow(() -> DomainException.unauthorized("Invalid or revoked API key"));
            usage.recordApiCall(server.tenant());
            return Optional.of(token(server, "ROLE_TENANT_SERVER"));
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return Optional.empty();
        }
        return Optional.of(switch (jwt.verify(header.substring(BEARER.length()).trim())) {
            case Principals.AdminPrincipal admin -> {
                // The role comes from the database, not the token: deleted or demoted admins lose access quickly.
                AdminUser.Role role = admins.currentRole(admin.id())
                        .orElseThrow(() -> DomainException.unauthorized("Admin account no longer exists"));
                yield token(admin.withRole(role), "ROLE_" + role.name());
            }
            case Principals.ChatUserPrincipal user -> {
                tenants.find(user.tenant())
                        .orElseThrow(() -> DomainException.unauthorized("Tenant no longer exists"))
                        .requireUsable();
                users.requireActiveUser(user.tenant(), user.userId());
                yield token(user, "ROLE_CHAT_USER");
            }
        });
    }

    private static UsernamePasswordAuthenticationToken token(Principal principal, String authority) {
        return new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority(authority)));
    }
}
