package com.ayush.chat.auth;

import java.security.Principal;

/**
 * The three kinds of authenticated callers.
 */
public final class Principals {

    private Principals() {
    }

    public record AdminPrincipal(Long id, String email, AdminUser.Role role) implements Principal {
        @Override
        public String getName() {
            return email;
        }
    }

    public record TenantServerPrincipal(String tenant, Long apiKeyId, String keyName) implements Principal {
        @Override
        public String getName() {
            return "apikey:" + tenant + ":" + keyName;
        }
    }

    /**
     * An end user. {@link #getName()} is the STOMP user name, namespaced by tenant so user
     * destinations can never cross tenants.
     */
    public record ChatUserPrincipal(String tenant, String userId) implements Principal {

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
