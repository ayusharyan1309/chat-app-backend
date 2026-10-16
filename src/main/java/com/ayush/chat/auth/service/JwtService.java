package com.ayush.chat.auth.service;

import com.ayush.chat.auth.dto.IssuedToken;
import com.ayush.chat.auth.entity.AdminUser;
import com.ayush.chat.auth.security.Principals;
import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.crypto.KeyRing;
import com.ayush.chat.core.exception.DomainException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies platform JWTs (HS256). Admin and user tokens use different audiences so
 * one can never be replayed as the other. TTL policy for user tokens lives in
 * {@link UserTokenService}; this class only signs and parses.
 */
@Slf4j
@Service
public class JwtService {

    static final String ISSUER = "chat-platform";
    static final String AUD_ADMIN = "admin";
    static final String AUD_USER = "user";

    private final SecretKey key;
    private final Duration adminTokenTtl;

    public JwtService(PlatformProperties properties, KeyRing keyRing) {
        this.adminTokenTtl = properties.getSecurity().getAdminTokenTtl();
        String secret = properties.getSecurity().getJwtSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("platform.security.jwt-secret is not set — deriving it from the master key. Set PLATFORM_JWT_SECRET in production.");
            this.key = Keys.hmacShaKeyFor(keyRing.derive(KeyRing.Purpose.JWT));
        } else {
            this.key = Keys.hmacShaKeyFor(KeyRing.fromSecret(secret));
        }
    }

    public IssuedToken issueAdmin(AdminUser admin) {
        Instant exp = Instant.now().plus(adminTokenTtl);
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

    IssuedToken issueUser(String tenant, String userId, Duration ttl) {
        Instant exp = Instant.now().plus(ttl);
        String token = Jwts.builder()
                .issuer(ISSUER).audience().add(AUD_USER).and()
                .subject(userId)
                .claim("tid", tenant)
                .issuedAt(new Date()).expiration(Date.from(exp))
                .id(UUID.randomUUID().toString())
                .signWith(key).compact();
        return new IssuedToken(token, exp);
    }

    /** Returns the principal the token was issued for, or throws 401. */
    public Principals.TokenPrincipal verify(String token) {
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(key).requireIssuer(ISSUER).build().parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw DomainException.unauthorized("Invalid or expired token");
        }
        if (claims.getAudience().contains(AUD_ADMIN)) {
            return new Principals.AdminPrincipal(Long.valueOf(claims.getSubject()), claims.get("email", String.class),
                    AdminUser.Role.valueOf(claims.get("role", String.class)));
        }
        if (claims.getAudience().contains(AUD_USER)) {
            return new Principals.ChatUserPrincipal(claims.get("tid", String.class), claims.getSubject());
        }
        throw DomainException.unauthorized("Unknown token audience");
    }
}
