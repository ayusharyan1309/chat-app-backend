package com.ayush.chat.chat.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Realtime envelope pushed to clients on {@code /user/queue/events}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatEvent(String type, String conversationId, Instant at, Object data) {

    public static final String MESSAGE_NEW = "message.new";
    public static final String MESSAGE_ACK = "message.ack";
    public static final String MESSAGE_UPDATED = "message.updated";
    public static final String RECEIPT = "receipt";
    public static final String TYPING = "typing";
    public static final String PRESENCE = "presence";
    public static final String CONVERSATION_UPDATED = "conversation.updated";
    public static final String CONVERSATION_REMOVED = "conversation.removed";
    public static final String ERROR = "error";

    public static ChatEvent of(String type, String conversationId, Object data) {
        return new ChatEvent(type, conversationId, Instant.now(), data);
    }
}
