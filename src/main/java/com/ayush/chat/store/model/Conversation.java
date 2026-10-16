package com.ayush.chat.store.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * A direct (1:1) or group conversation. {@link #lastSeq} is the highest message sequence number
 * allocated in this conversation; sequences are dense and strictly increasing per conversation.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Conversation {

    public enum Type { DIRECT, GROUP }

    private String tenantId;
    private String id;
    private Type type;
    /** Deterministic key for DIRECT conversations ({@link #directKey(String, String)}); null for groups. */
    private String directKey;
    private String title;
    private String description;
    private String avatarUrl;
    private String createdBy;
    private long lastSeq;
    private String lastMessageId;
    private String lastMessagePreview;
    private String lastMessageSenderId;
    private Instant lastMessageAt;
    private Map<String, Object> metadata;
    private Instant createdAt;
    private Instant updatedAt;

    public static String directKey(String userA, String userB) {
        return userA.compareTo(userB) <= 0 ? userA + "|" + userB : userB + "|" + userA;
    }

    /**
     * When the conversation last saw activity: its last message, or its creation when it has none.
     * Orders the inbox and is the inbox paging cursor. (Deliberately not a bean getter, so it is
     * never serialised as a field.)
     */
    public Instant activityAt() {
        return lastMessageAt != null ? lastMessageAt : createdAt;
    }
}
