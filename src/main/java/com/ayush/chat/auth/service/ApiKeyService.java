package com.ayush.chat.auth.service;

import com.ayush.chat.auth.dto.CreatedApiKey;
import com.ayush.chat.auth.entity.ApiKey;
import com.ayush.chat.auth.repository.ApiKeyRepository;
import com.ayush.chat.auth.security.Principals;
import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.crypto.SecretCipher;
import com.ayush.chat.core.exception.DomainException;
import com.ayush.chat.core.util.Ids;
import com.ayush.chat.tenant.entity.Tenant;
import com.ayush.chat.tenant.repository.TenantRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tenant API keys: {@code ck_live_<random>}. Only a SHA-256 hash is stored. Lookups use a short
 * positive cache; revocation clears it locally and takes effect cluster-wide within its TTL.
 */
@Service
public class ApiKeyService {

    static final String PREFIX = "ck_live_";
    private static final int VISIBLE_PREFIX = PREFIX.length() + 6;
    /** Write last-used at most this often per key, keeping writes off the hot path. */
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final ApiKeyRepository keys;
    private final TenantRepository tenants;
    private final Cache<String, Optional<Principals.TenantServerPrincipal>> cache;
    private final Map<Long, Instant> lastTouched = new ConcurrentHashMap<>();

    public ApiKeyService(ApiKeyRepository keys, TenantRepository tenants, PlatformProperties properties) {
        this.keys = keys;
        this.tenants = tenants;
        this.cache = Caffeine.newBuilder().expireAfterWrite(properties.getCaches().getApiKeys()).maximumSize(50_000).build();
    }

    @Transactional
    public CreatedApiKey create(String tenant, String name, String actor) {
        if (name == null || name.isBlank()) {
            throw DomainException.badRequest("name is required");
        }
        Tenant owner = tenants.findBySlug(tenant).filter(t -> t.getStatus() != Tenant.Status.DELETED)
                .orElseThrow(() -> DomainException.notFound("Tenant '" + tenant + "'"));
        String plaintext = PREFIX + Ids.randomToken(40);
        ApiKey key = new ApiKey();
        key.setTenant(owner);
        key.setName(name.trim());
        key.setKeyPrefix(plaintext.substring(0, VISIBLE_PREFIX));
        key.setKeyHash(SecretCipher.sha256(plaintext));
        key.setCreatedBy(actor);
        keys.save(key);
        return new CreatedApiKey(key, plaintext);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list(String tenant) {
        return keys.findByTenantSlug(tenant);
    }

    @Transactional
    public ApiKey revoke(String tenant, Long keyId) {
        ApiKey key = keys.findByIdAndTenantSlug(keyId, tenant).orElseThrow(() -> DomainException.notFound("API key"));
        if (key.getRevokedAt() == null) {
            key.setRevokedAt(Instant.now());
        }
        cache.invalidateAll();
        return key;
    }

    @Transactional
    public void revokeAll(String tenant) {
        keys.revokeAll(tenant, Instant.now());
        cache.invalidateAll();
    }

    /** Resolve a presented key; empty when unknown, revoked or the tenant was deleted (suspension is a 403 later). */
    @Transactional
    public Optional<Principals.TenantServerPrincipal> authenticate(String presented) {
        if (presented == null || !presented.startsWith(PREFIX)) {
            return Optional.empty();
        }
        Optional<Principals.TenantServerPrincipal> principal = cache.get(SecretCipher.sha256(presented), hash -> keys.findByHash(hash)
                .filter(k -> k.getRevokedAt() == null)
                .filter(k -> k.getTenant().getStatus() != Tenant.Status.DELETED)
                .map(k -> new Principals.TenantServerPrincipal(k.getTenant().getSlug(), k.getId(), k.getName())));
        principal.ifPresent(p -> touch(p.apiKeyId()));
        return principal;
    }

    private void touch(Long keyId) {
        Instant now = Instant.now();
        Instant previous = lastTouched.get(keyId);
        if (previous == null || previous.isBefore(now.minus(TOUCH_INTERVAL))) {
            lastTouched.put(keyId, now);
            keys.touch(keyId, now);
        }
    }
}
