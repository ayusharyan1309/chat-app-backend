package com.ayush.chat.chat.dto.socket;

import com.ayush.chat.store.model.ChatMessage;

import java.util.Map;

public record SendMessageFrame(String conversationId, String clientMsgId, ChatMessage.Type type, String content,
                               ChatMessage.Attachment attachment, String replyToId, Boolean forwarded,
                               Map<String, Object> metadata) {
}
