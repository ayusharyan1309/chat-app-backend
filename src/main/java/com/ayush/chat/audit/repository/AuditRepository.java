package com.ayush.chat.audit.repository;

import com.ayush.chat.audit.entity.AuditEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditRepository extends JpaRepository<AuditEntry, Long> {

    @Query("select a from AuditEntry a where (:tenant is null or a.tenant = :tenant) and (:before is null or a.id < :before)"
            + " order by a.id desc")
    List<AuditEntry> page(@Param("tenant") String tenant, @Param("before") Long before, Pageable page);
}
