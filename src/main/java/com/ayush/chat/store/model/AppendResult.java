package com.ayush.chat.store.model;

/**
 * Result of {@code appendMessage}. {@code duplicate} is true when a message with the same
 * (conversation, sender, clientMsgId) already existed and was returned instead of a new one.
 */
public record AppendResult(ChatMessage message, boolean duplicate) {
}
