package com.ayush.chat.store.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Membership of a user in a conversation, plus that user's per-conversation state.
 * Receipts are watermarks: every message with {@code seq <= readSeq} has been read by this user.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Participant {

    public enum Role { OWNER, ADMIN, MEMBER }

    private String tenantId;
    private String conversationId;
    private String userId;
    @Builder.Default
    private Role role = Role.MEMBER;
    /** Messages with seq <= joinedSeq predate this membership and are hidden from the user. */
    private long joinedSeq;
    private long deliveredSeq;
    private long readSeq;
    private boolean muted;
    private boolean archived;
    private boolean pinned;
    private Instant joinedAt;
    /** Non-null when the user has left or was removed. */
    private Instant leftAt;
    private Instant updatedAt;

    public boolean isActive() {
        return leftAt == null;
    }

    public boolean isAdmin() {
        return role == Role.OWNER || role == Role.ADMIN;
    }
}
