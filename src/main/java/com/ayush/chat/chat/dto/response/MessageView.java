package com.ayush.chat.chat.dto.response;

import com.ayush.chat.store.model.ChatMessage;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record MessageView(String id, String conversationId, long seq, String senderId, String clientMsgId,
                          ChatMessage.Type type, String content, ChatMessage.Attachment attachment, String replyToId,
                          boolean forwarded, Map<String, Set<String>> reactions, boolean edited, Instant editedAt,
                          boolean deleted, Instant createdAt, Map<String, Object> metadata) {

    public static MessageView of(ChatMessage m) {
        return new MessageView(m.getId(), m.getConversationId(), m.getSeq(), m.getSenderId(), m.getClientMsgId(),
                m.getType(), m.getContent(), m.getAttachment(), m.getReplyToId(), m.isForwarded(),
                m.getReactions() == null ? Map.of() : m.getReactions(), m.isEdited(), m.getEditedAt(), m.isDeleted(),
                m.getCreatedAt(), m.getMetadata());
    }
}
