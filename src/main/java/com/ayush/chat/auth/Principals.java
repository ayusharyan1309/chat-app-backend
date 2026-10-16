package com.ayush.chat.auth;

import com.ayush.chat.audit.AuditActor;

import java.security.Principal;

/**
 * The three kinds of authenticated callers. Token verification yields a {@link TokenPrincipal}
 * (admin or end user); API keys yield a {@link TenantServerPrincipal}.
 */
public final class Principals {

    private Principals() {
    }

    /** Principals carried by a platform JWT. */
    public sealed interface TokenPrincipal extends Principal permits AdminPrincipal, ChatUserPrincipal {
    }

    public record AdminPrincipal(Long id, String email, AdminUser.Role role) implements TokenPrincipal, AuditActor {
        @Override
        public String getName() {
            return email;
        }

        @Override
        public String auditName() {
            return email;
        }

        @Override
        public String auditType() {
            return "ADMIN";
        }

        public AdminPrincipal withRole(AdminUser.Role current) {
            return new AdminPrincipal(id, email, current);
        }
    }

    public record TenantServerPrincipal(String tenant, Long apiKeyId, String keyName) implements Principal, AuditActor {
        @Override
        public String getName() {
            return "apikey:" + tenant + ":" + keyName;
        }

        @Override
        public String auditName() {
            return "api-key:" + keyName;
        }

        @Override
        public String auditType() {
            return "API_KEY";
        }
    }

    /**
     * An end user. {@link #getName()} is the STOMP user name, namespaced by tenant so user
     * destinations can never cross tenants.
     */
    public record ChatUserPrincipal(String tenant, String userId) implements TokenPrincipal {

        public static final String SEPARATOR = "::";

        @Override
        public String getName() {
            return key(tenant, userId);
        }

        public static String key(String tenant, String userId) {
            return tenant + SEPARATOR + userId;
        }
    }
}
