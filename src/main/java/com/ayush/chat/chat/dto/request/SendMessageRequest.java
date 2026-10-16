package com.ayush.chat.chat.dto.request;

import com.ayush.chat.store.model.ChatMessage;

import java.util.Map;

public record SendMessageRequest(String clientMsgId, ChatMessage.Type type, String content, ChatMessage.Attachment attachment,
                                 String replyToId, Boolean forwarded, Map<String, Object> metadata) {
}
