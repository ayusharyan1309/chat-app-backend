package com.ayush.chat.chat.dto.response;

public record ReceiptView(String userId, long deliveredSeq, long readSeq) {
}
