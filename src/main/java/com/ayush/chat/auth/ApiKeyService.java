package com.ayush.chat.auth;

import com.ayush.chat.auth.AuthRepositories.ApiKeyRepository;
import com.ayush.chat.core.crypto.SecretCipher;
import com.ayush.chat.core.error.ApiException;
import com.ayush.chat.core.util.Ids;
import com.ayush.chat.tenant.Tenant;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tenant API keys: {@code ck_live_<random>}. Lookups are by SHA-256 hash with a short positive
 * cache; revocations invalidate it locally and take effect cluster-wide within the cache TTL.
 */
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final String PREFIX = "ck_live_";

    private final ApiKeyRepository keys;

    private final Cache<String, Optional<Principals.TenantServerPrincipal>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30)).maximumSize(50_000).build();
    private final Map<Long, Instant> lastTouched = new ConcurrentHashMap<>();

    public record CreatedKey(ApiKey key, String plaintext) {
    }

    @Transactional
    public CreatedKey create(Tenant tenant, String name, String actor) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name is required");
        }
        String plaintext = PREFIX + Ids.randomToken(40);
        ApiKey key = new ApiKey();
        key.setTenant(tenant);
        key.setName(name.trim());
        key.setKeyPrefix(plaintext.substring(0, PREFIX.length() + 6));
        key.setKeyHash(SecretCipher.sha256(plaintext));
        key.setCreatedBy(actor);
        keys.save(key);
        return new CreatedKey(key, plaintext);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list(Tenant tenant) {
        return keys.findByTenantId(tenant.getId());
    }

    @Transactional
    public void revoke(Tenant tenant, Long keyId) {
        ApiKey key = keys.findById(keyId).filter(k -> k.getTenant().getId().equals(tenant.getId()))
                .orElseThrow(() -> ApiException.notFound("API key"));
        if (key.getRevokedAt() == null) {
            key.setRevokedAt(Instant.now());
            keys.save(key);
        }
        cache.invalidateAll();
    }

    @Transactional
    public void revokeAll(Tenant tenant) {
        keys.revokeAll(tenant.getId(), Instant.now());
        cache.invalidateAll();
    }

    /** Resolve a presented key; empty when unknown, revoked or the tenant is not usable. */
    @Transactional
    public Optional<Principals.TenantServerPrincipal> authenticate(String presented) {
        if (presented == null || !presented.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String hash = SecretCipher.sha256(presented);
        Optional<Principals.TenantServerPrincipal> principal = cache.get(hash, h -> keys.findByHash(h)
                .filter(k -> k.getRevokedAt() == null)
                .filter(k -> k.getTenant().isUsable())
                .map(k -> new Principals.TenantServerPrincipal(k.getTenant().getSlug(), k.getId(), k.getName())));
        principal.ifPresent(p -> touch(p.apiKeyId()));
        return principal;
    }

    /** Record last use at most once a minute per key to keep writes off the hot path. */
    private void touch(Long keyId) {
        Instant now = Instant.now();
        Instant previous = lastTouched.get(keyId);
        if (previous == null || previous.isBefore(now.minusSeconds(60))) {
            lastTouched.put(keyId, now);
            keys.touch(keyId, now);
        }
    }
}
