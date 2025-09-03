package com.ayush.chat.kafka.kafka;

import com.ayush.chat.dto.request.chat.ReadReceiptDto;
import com.ayush.chat.service.chat.MessageServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class ReadReceiptKafkaConsumer {
    @Autowired
    private MessageServiceImpl messageServiceImpl;

    @KafkaListener(topics = "chat-read-receipts", groupId = "chat-group")
    public void consume(ReadReceiptDto receiptDto) {
        if (receiptDto == null || receiptDto.getMessageIds() == null || receiptDto.getMessageIds().isEmpty()) return;
        messageServiceImpl.markMessagesAsRead(receiptDto.getMessageIds());
    }
}
