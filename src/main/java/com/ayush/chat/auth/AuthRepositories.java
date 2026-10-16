package com.ayush.chat.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class AuthRepositories {

    private AuthRepositories() {
    }

    public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {
        Optional<AdminUser> findByEmailIgnoreCase(String email);

        long countByRoleAndActiveTrue(AdminUser.Role role);
    }

    public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {
        @Query("select k from ApiKey k join fetch k.tenant where k.keyHash = :hash")
        Optional<ApiKey> findByHash(@Param("hash") String hash);

        @Query("select k from ApiKey k where k.tenant.id = :tenantId order by k.createdAt desc")
        List<ApiKey> findByTenantId(@Param("tenantId") Long tenantId);

        @Modifying
        @Query("update ApiKey k set k.lastUsedAt = :at where k.id = :id")
        void touch(@Param("id") Long id, @Param("at") Instant at);

        @Modifying
        @Query("update ApiKey k set k.revokedAt = :at where k.tenant.id = :tenantId and k.revokedAt is null")
        void revokeAll(@Param("tenantId") Long tenantId, @Param("at") Instant at);
    }
}
