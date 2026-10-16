package com.ayush.chat.auth;

import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.tenant.TenantConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParserBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.ProtectedHeader;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies end-user tokens issued by the tenant's own identity provider:
 * Firebase ID tokens (Google's JWKS) or arbitrary JWTs via JWKS (RS/ES) or a shared HS256 secret.
 */
@Slf4j
@Service
public class ExternalTokenVerifier {

    static final String FIREBASE_JWKS = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";

    private static final long CLOCK_SKEW_SECONDS = 60;

    private final PlatformProperties.Jwks settings;
    private final HttpClient http;
    private final Map<String, CachedJwks> jwksCache = new ConcurrentHashMap<>();

    public ExternalTokenVerifier(PlatformProperties properties) {
        this.settings = properties.getJwks();
        this.http = HttpClient.newBuilder().connectTimeout(settings.getFetchTimeout()).build();
    }

    public record Identity(String userId, String displayName, String email) {
    }

    private record CachedJwks(Map<String, Key> keys, Instant fetchedAt) {
    }

    public Identity verify(TenantConfig.External config, String token) {
        JwtParserBuilder parser = Jwts.parser().clockSkewSeconds(CLOCK_SKEW_SECONDS);
        switch (config.typeOrNone()) {
            case FIREBASE -> {
                String project = config.firebaseProjectId();
                parser.keyLocator(locator(FIREBASE_JWKS))
                        .requireIssuer("https://securetoken.google.com/" + project)
                        .requireAudience(project);
            }
            case JWT -> {
                if (config.jwksUrl() != null && !config.jwksUrl().isBlank()) {
                    parser.keyLocator(locator(config.jwksUrl()));
                } else if (config.jwtSecret() != null) {
                    parser.verifyWith(Keys.hmacShaKeyFor(config.jwtSecret().getBytes(StandardCharsets.UTF_8)));
                } else {
                    throw DomainException.badRequest("Tenant JWT auth is not configured");
                }
                if (config.jwtIssuer() != null) {
                    parser.requireIssuer(config.jwtIssuer());
                }
                if (config.jwtAudience() != null) {
                    parser.requireAudience(config.jwtAudience());
                }
            }
            case NONE -> throw DomainException.badRequest("External authentication is not enabled for this tenant");
        }
        Claims claims;
        try {
            claims = parser.build().parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw DomainException.unauthorized("External token rejected: " + e.getMessage());
        }
        String userId = claimString(claims, config.userIdClaim() == null ? "sub" : config.userIdClaim());
        if (userId == null || userId.isBlank()) {
            throw DomainException.unauthorized("External token has no user id claim");
        }
        return new Identity(userId, claimString(claims, config.nameClaim() == null ? "name" : config.nameClaim()),
                claimString(claims, config.emailClaim() == null ? "email" : config.emailClaim()));
    }

    private static String claimString(Claims claims, String name) {
        Object value = claims.get(name);
        return value == null ? null : String.valueOf(value);
    }

    private LocatorAdapter<Key> locator(String jwksUrl) {
        return new LocatorAdapter<>() {
            @Override
            protected Key locate(ProtectedHeader header) {
                String kid = header.getKeyId();
                Key key = keys(jwksUrl, false).get(kid);
                if (key == null) {
                    key = keys(jwksUrl, true).get(kid);
                }
                if (key == null) {
                    throw new JwtException("Unknown signing key id: " + kid);
                }
                return key;
            }
        };
    }

    private Map<String, Key> keys(String url, boolean refresh) {
        CachedJwks cached = jwksCache.get(url);
        boolean stale = cached == null || cached.fetchedAt().isBefore(Instant.now().minus(settings.getCacheTtl()));
        // Unknown key ids trigger a refresh (key rotation), but at most once per interval per URL.
        boolean refreshAllowed = cached == null || cached.fetchedAt().isBefore(Instant.now().minus(settings.getMinRefreshInterval()));
        if (stale || (refresh && refreshAllowed)) {
            try {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(settings.getFetchTimeout()).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                JwkSet set = Jwks.setParser().build().parse(response.body());
                Map<String, Key> keys = new HashMap<>();
                for (Jwk<?> jwk : set.getKeys()) {
                    keys.put(jwk.getId(), jwk.toKey());
                }
                cached = new CachedJwks(keys, Instant.now());
                jwksCache.put(url, cached);
            } catch (Exception e) {
                log.warn("Fetching JWKS {} failed: {}", url, e.getMessage());
                if (cached == null) {
                    throw DomainException.unavailable("Cannot fetch signing keys from " + url);
                }
            }
        }
        return cached.keys();
    }
}
