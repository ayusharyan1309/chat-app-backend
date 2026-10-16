package com.ayush.chat.chat.dto.request;

import com.ayush.chat.store.model.ChatMessage;

import java.util.Map;

public record ServerMessageRequest(String senderId, ChatMessage.Type type, String content, String clientMsgId,
                                   ChatMessage.Attachment attachment, Map<String, Object> metadata) {
}
