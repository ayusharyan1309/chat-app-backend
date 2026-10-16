package com.ayush.chat.chat.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WebhookRepository extends JpaRepository<Webhook, Long> {

    @Query("select w from Webhook w where w.tenant.slug = :slug order by w.id")
    List<Webhook> findByTenantSlug(@Param("slug") String slug);

    @Query("select w from Webhook w where w.id = :id and w.tenant.slug = :slug")
    Optional<Webhook> findByIdAndTenantSlug(@Param("id") Long id, @Param("slug") String slug);

    @Query("select w from Webhook w where w.tenant.slug = :slug and w.active = true")
    List<Webhook> findActiveByTenantSlug(@Param("slug") String slug);
}
