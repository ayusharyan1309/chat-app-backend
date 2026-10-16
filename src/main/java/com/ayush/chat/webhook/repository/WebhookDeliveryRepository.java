package com.ayush.chat.webhook.repository;

import com.ayush.chat.webhook.entity.WebhookDelivery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    @Query("select d from WebhookDelivery d where d.webhookId = :webhookId order by d.id desc")
    List<WebhookDelivery> recent(@Param("webhookId") Long webhookId, Pageable page);

    @Modifying
    @Query("delete from WebhookDelivery d where d.createdAt < :before")
    int purgeBefore(@Param("before") Instant before);
}
