package com.ayush.chat.audit.dto;

import com.ayush.chat.audit.entity.AuditEntry;

import java.time.Instant;

public record AuditEntryView(Long id, Instant at, String actor, String actorType, String action, String targetType,
                             String targetId, String tenant, String details, String ip) {

    public static AuditEntryView of(AuditEntry e) {
        return new AuditEntryView(e.getId(), e.getAt(), e.getActor(), e.getActorType(), e.getAction(), e.getTargetType(),
                e.getTargetId(), e.getTenant(), e.getDetails(), e.getIp());
    }
}
