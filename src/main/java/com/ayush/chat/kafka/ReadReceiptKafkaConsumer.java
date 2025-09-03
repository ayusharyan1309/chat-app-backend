package com.ayush.chat.kafka;

import com.ayush.chat.dto.request.chat.ReadReceiptDto;
import com.ayush.chat.service.chat.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class ReadReceiptKafkaConsumer {
    @Autowired
    private MessageService messageService;

    @KafkaListener(topics = "chat-read-receipts", groupId = "chat-group")
    public void consume(ReadReceiptDto receiptDto) {
        if (receiptDto == null || receiptDto.getMessageIds() == null || receiptDto.getMessageIds().isEmpty()) return;
        messageService.markMessagesAsRead(receiptDto.getMessageIds());
    }
}
