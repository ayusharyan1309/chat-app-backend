package com.ayush.chat.audit.service;

import com.ayush.chat.audit.dto.AuditEntryView;
import com.ayush.chat.audit.entity.AuditEntry;
import com.ayush.chat.audit.model.AuditAction;
import com.ayush.chat.audit.model.AuditActor;
import com.ayush.chat.audit.repository.AuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;

/**
 * Append-only record of administrative and server-API actions. Services record inside their own
 * transaction, so an action that rolls back leaves no audit entry behind.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final int MAX_DETAILS = 2000;
    private static final int MAX_PAGE = 500;

    private final AuditRepository repository;

    public void record(AuditAction action, String targetId, String tenant, String details) {
        AuditEntry entry = new AuditEntry();
        entry.setAt(Instant.now());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuditActor actor) {
            entry.setActor(actor.auditName());
            entry.setActorType(actor.auditType());
        } else {
            entry.setActor("system");
            entry.setActorType("SYSTEM");
        }
        entry.setAction(action.id());
        entry.setTargetType(action.targetType());
        entry.setTargetId(targetId);
        entry.setTenant(tenant);
        entry.setDetails(details == null || details.length() <= MAX_DETAILS ? details
                : details.substring(0, MAX_DETAILS - 3) + "...");
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            entry.setIp(attrs.getRequest().getRemoteAddr());
        }
        repository.save(entry);
    }

    @Transactional(readOnly = true)
    public List<AuditEntryView> list(String tenant, Long before, int limit) {
        return repository.page(tenant == null || tenant.isBlank() ? null : tenant, before,
                        PageRequest.of(0, Math.max(1, Math.min(limit, MAX_PAGE))))
                .stream().map(AuditEntryView::of).toList();
    }

    /** Name of the authenticated caller, for "created by" columns. */
    public static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }
}
