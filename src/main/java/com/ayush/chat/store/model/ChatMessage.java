package com.ayush.chat.store.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A message. {@link #seq} is assigned by the store on append and orders messages within the
 * conversation; {@link #clientMsgId} (unique per sender+conversation) makes sends idempotent.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    public enum Type { TEXT, IMAGE, VIDEO, AUDIO, FILE, LOCATION, CONTACT, STICKER, SYSTEM }

    private String tenantId;
    private String id;
    private String conversationId;
    private long seq;
    private String senderId;
    private String clientMsgId;
    @Builder.Default
    private Type type = Type.TEXT;
    /** Text body or caption. May be ciphertext when clients use end-to-end encryption. */
    private String content;
    private Attachment attachment;
    private String replyToId;
    private boolean forwarded;
    /** emoji -> user ids who reacted with it. */
    @Builder.Default
    private Map<String, Set<String>> reactions = new LinkedHashMap<>();
    private boolean edited;
    private Instant editedAt;
    private boolean deleted;
    private Instant deletedAt;
    private Map<String, Object> metadata;
    private Instant createdAt;

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Attachment {
        private String mediaId;
        private String url;
        private String mimeType;
        private Long size;
        private String fileName;
        private Integer width;
        private Integer height;
        private Integer durationMs;
        private String thumbnailUrl;
    }

    public void addReaction(String emoji, String userId) {
        reactions.values().forEach(users -> users.remove(userId));
        reactions.computeIfAbsent(emoji, k -> new LinkedHashSet<>()).add(userId);
        reactions.values().removeIf(Set::isEmpty);
    }

    public void removeReaction(String userId) {
        reactions.values().forEach(users -> users.remove(userId));
        reactions.values().removeIf(Set::isEmpty);
    }
}
