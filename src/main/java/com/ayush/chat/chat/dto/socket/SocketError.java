package com.ayush.chat.chat.dto.socket;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SocketError(String code, String message, String clientMsgId) {
}
