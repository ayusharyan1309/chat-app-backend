package com.ayush.chat.admin;

import com.ayush.chat.auth.Principals;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;

/**
 * Append-only record of administrative and server-API actions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditRepository repository;

    public interface AuditRepository extends JpaRepository<AuditEntry, Long> {
        @Query("select a from AuditEntry a where (:tenant is null or a.tenant = :tenant) and (:before is null or a.id < :before)"
                + " order by a.id desc")
        List<AuditEntry> page(@Param("tenant") String tenant, @Param("before") Long before, PageRequest page);
    }

    public void record(String action, String targetType, String targetId, String tenant, String details) {
        AuditEntry entry = new AuditEntry();
        entry.setAt(Instant.now());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth == null ? null : auth.getPrincipal();
        if (principal instanceof Principals.AdminPrincipal admin) {
            entry.setActor(admin.email());
            entry.setActorType("ADMIN");
        } else if (principal instanceof Principals.TenantServerPrincipal server) {
            entry.setActor("api-key:" + server.keyName());
            entry.setActorType("API_KEY");
        } else {
            entry.setActor("system");
            entry.setActorType("SYSTEM");
        }
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setTenant(tenant);
        entry.setDetails(details == null || details.length() <= 2000 ? details : details.substring(0, 1997) + "...");
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            String forwarded = attrs.getRequest().getHeader("X-Forwarded-For");
            entry.setIp(forwarded != null ? forwarded.split(",")[0].trim() : attrs.getRequest().getRemoteAddr());
        }
        try {
            repository.save(entry);
        } catch (Exception e) {
            log.error("Failed to write audit entry {} {}", action, targetId, e);
        }
    }

    public List<AuditEntry> list(String tenant, Long before, int limit) {
        return repository.page(tenant == null || tenant.isBlank() ? null : tenant, before,
                PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
    }

    public static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }
}
