package com.ayush.chat.chat.dto.socket;

import com.ayush.chat.chat.dto.response.MessageView;

/** Sent to the sender's own sessions once a message is durable. */
public record MessageAck(String clientMsgId, MessageView message, boolean duplicate) {
}
