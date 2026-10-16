package com.ayush.chat.chat.dto.command;

import com.ayush.chat.store.model.ChatMessage;

import java.util.Map;

public record SendMessageCommand(String clientMsgId, ChatMessage.Type type, String content, ChatMessage.Attachment attachment,
                                 String replyToId, Boolean forwarded, Map<String, Object> metadata) {
}
