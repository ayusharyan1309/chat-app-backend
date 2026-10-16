package com.ayush.chat.chat.dto.command;

import com.ayush.chat.chat.dto.response.MessageView;

public record SendMessageResult(MessageView message, boolean duplicate) {
}
