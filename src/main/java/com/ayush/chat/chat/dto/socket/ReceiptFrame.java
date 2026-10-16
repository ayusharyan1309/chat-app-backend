package com.ayush.chat.chat.dto.socket;

public record ReceiptFrame(String conversationId, long seq, String kind) {
}
