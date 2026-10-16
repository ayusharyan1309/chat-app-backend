package com.ayush.chat.auth.service;

import com.ayush.chat.auth.dto.IssuedToken;
import com.ayush.chat.auth.security.ActiveUserCheck;
import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * The single place end-user tokens are minted, so every path (server API, logins, impersonation)
 * applies the same rules: the user must be active and the lifetime must be within
 * {@code platform.security.min/max-user-token-ttl}.
 */
@Service
@RequiredArgsConstructor
public class UserTokenService {

    private final JwtService jwt;
    private final ActiveUserCheck users;
    private final PlatformProperties properties;

    /** {@code ttl} null means the platform default. */
    public IssuedToken issue(String tenant, String userId, Duration ttl) {
        PlatformProperties.Security security = properties.getSecurity();
        Duration lifetime = ttl != null ? ttl : security.getUserTokenTtl();
        if (lifetime.compareTo(security.getMinUserTokenTtl()) < 0 || lifetime.compareTo(security.getMaxUserTokenTtl()) > 0) {
            throw DomainException.badRequest("Token lifetime must be between " + security.getMinUserTokenTtl().toMinutes()
                    + " and " + security.getMaxUserTokenTtl().toMinutes() + " minutes");
        }
        users.requireActiveUser(tenant, userId);
        return jwt.issueUser(tenant, userId, lifetime);
    }

    /** Short-lived token an operator uses to act as a user (dashboard playground); callers audit it. */
    public IssuedToken impersonate(String tenant, String userId) {
        users.requireActiveUser(tenant, userId);
        return jwt.issueUser(tenant, userId, properties.getSecurity().getImpersonationTokenTtl());
    }
}
