package com.ayush.chat.auth;

import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.error.ApiException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies platform JWTs (HS256). Admin and user tokens use different audiences so
 * one can never be replayed as the other.
 */
@Slf4j
@Service
public class JwtService {

    static final String ISSUER = "chat-platform";
    static final String AUD_ADMIN = "admin";
    static final String AUD_USER = "user";

    private final SecretKey key;
    private final PlatformProperties properties;

    public record IssuedToken(String token, Instant expiresAt) {
    }

    public JwtService(PlatformProperties properties) {
        this.properties = properties;
        String secret = properties.getSecurity().getJwtSecret();
        String material = secret != null && !secret.isBlank() ? secret
                : "jwt:" + properties.getSecurity().effectiveMasterKey();
        if (secret == null || secret.isBlank()) {
            log.warn("platform.security.jwt-secret is not set — deriving it from the master key. Set PLATFORM_JWT_SECRET in production.");
        }
        try {
            this.key = Keys.hmacShaKeyFor(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public IssuedToken issueAdmin(AdminUser admin) {
        Instant exp = Instant.now().plus(properties.getSecurity().getAdminTokenTtl());
        String token = Jwts.builder()
                .issuer(ISSUER).audience().add(AUD_ADMIN).and()
                .subject(String.valueOf(admin.getId()))
                .claim("email", admin.getEmail())
                .claim("role", admin.getRole().name())
                .issuedAt(new Date()).expiration(Date.from(exp))
                .id(UUID.randomUUID().toString())
                .signWith(key).compact();
        return new IssuedToken(token, exp);
    }

    public IssuedToken issueUser(String tenant, String userId, Duration ttl) {
        Duration effective = ttl != null ? ttl : properties.getSecurity().getUserTokenTtl();
        Instant exp = Instant.now().plus(effective);
        String token = Jwts.builder()
                .issuer(ISSUER).audience().add(AUD_USER).and()
                .subject(userId)
                .claim("tid", tenant)
                .issuedAt(new Date()).expiration(Date.from(exp))
                .id(UUID.randomUUID().toString())
                .signWith(key).compact();
        return new IssuedToken(token, exp);
    }

    /** Returns the matching principal, or throws 401. */
    public Object verify(String token) {
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(key).requireIssuer(ISSUER).build().parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw ApiException.unauthorized("Invalid or expired token");
        }
        if (claims.getAudience().contains(AUD_ADMIN)) {
            return new Principals.AdminPrincipal(Long.valueOf(claims.getSubject()), claims.get("email", String.class),
                    AdminUser.Role.valueOf(claims.get("role", String.class)));
        }
        if (claims.getAudience().contains(AUD_USER)) {
            return new Principals.ChatUserPrincipal(claims.get("tid", String.class), claims.getSubject());
        }
        throw ApiException.unauthorized("Unknown token audience");
    }
}
