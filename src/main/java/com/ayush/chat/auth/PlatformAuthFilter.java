package com.ayush.chat.auth;

import com.ayush.chat.chat.ChatService;
import com.ayush.chat.core.error.ApiException;
import com.ayush.chat.tenant.TenantDirectory;
import com.ayush.chat.tenant.TenantSnapshot;
import com.ayush.chat.tenant.UsageService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
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

    private final JwtService jwt;
    private final ApiKeyService apiKeys;
    private final TenantDirectory tenants;
    private final UsageService usage;
    /** Providers: the filter is built with the security chain, before these services are ready. */
    private final ObjectProvider<ChatService> chat;
    private final ObjectProvider<AdminAuthService> admins;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            authenticate(request).ifPresent(auth -> SecurityContextHolder.getContext().setAuthentication(auth));
        } catch (ApiException e) {
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, e);
        }
        chain.doFilter(request, response);
    }

    private Optional<UsernamePasswordAuthenticationToken> authenticate(HttpServletRequest request) {
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            Principals.TenantServerPrincipal principal = apiKeys.authenticate(apiKey.trim())
                    .orElseThrow(() -> ApiException.unauthorized("Invalid or revoked API key"));
            usage.recordApiCall(principal.tenant());
            return Optional.of(token(principal, "ROLE_TENANT_SERVER"));
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        Object principal = jwt.verify(header.substring(7).trim());
        if (principal instanceof Principals.AdminPrincipal admin) {
            // Role comes from the database, not the token: deleted or demoted admins lose access quickly.
            AdminUser.Role role = admins.getObject().currentRole(admin.id())
                    .orElseThrow(() -> ApiException.unauthorized("Admin account no longer exists"));
            Principals.AdminPrincipal current = new Principals.AdminPrincipal(admin.id(), admin.email(), role);
            return Optional.of(token(current, "ROLE_" + role.name()));
        }
        Principals.ChatUserPrincipal user = (Principals.ChatUserPrincipal) principal;
        TenantSnapshot tenant = tenants.find(user.tenant())
                .orElseThrow(() -> ApiException.unauthorized("Tenant no longer exists"));
        if (!tenant.isUsable()) {
            throw ApiException.forbidden("Tenant '" + tenant.slug() + "' is " + tenant.status().name().toLowerCase());
        }
        chat.getObject().requireActiveUser(user.tenant(), user.userId());
        return Optional.of(token(user, "ROLE_CHAT_USER"));
    }

    private static UsernamePasswordAuthenticationToken token(Principal principal, String authority) {
        return new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority(authority)));
    }
}
