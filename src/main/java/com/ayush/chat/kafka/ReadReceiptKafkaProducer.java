package com.ayush.chat.kafka;

import com.ayush.chat.dto.request.chat.ReadReceiptDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReadReceiptKafkaProducer {
    private final KafkaTemplate<String, ReadReceiptDto> kafkaTemplate;

    @Autowired
    public ReadReceiptKafkaProducer(KafkaTemplate<String, ReadReceiptDto> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }


    /// TODO  appUserID + # unique something
    public void sendReadReceipt(ReadReceiptDto receipt) {
        kafkaTemplate.send("chat-read-receipts",receipt.getUserEmail(), receipt);
    }
}
