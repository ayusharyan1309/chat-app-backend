package com.ayush.chat.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    @Query("select k from ApiKey k join fetch k.tenant where k.keyHash = :hash")
    Optional<ApiKey> findByHash(@Param("hash") String hash);

    @Query("select k from ApiKey k where k.tenant.slug = :slug order by k.createdAt desc")
    List<ApiKey> findByTenantSlug(@Param("slug") String slug);

    @Query("select k from ApiKey k where k.id = :id and k.tenant.slug = :slug")
    Optional<ApiKey> findByIdAndTenantSlug(@Param("id") Long id, @Param("slug") String slug);

    @Modifying
    @Query("update ApiKey k set k.lastUsedAt = :at where k.id = :id")
    void touch(@Param("id") Long id, @Param("at") Instant at);

    @Modifying
    @Query("update ApiKey k set k.revokedAt = :at where k.revokedAt is null"
            + " and k.tenant.id in (select t.id from Tenant t where t.slug = :slug)")
    void revokeAll(@Param("slug") String slug, @Param("at") Instant at);
}
