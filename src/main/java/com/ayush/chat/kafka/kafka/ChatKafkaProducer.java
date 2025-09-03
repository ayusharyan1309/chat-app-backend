package com.ayush.chat.kafka.kafka;

import com.ayush.chat.dto.request.chat.ChatMessageDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;


@Service
public class ChatKafkaProducer {
    private final KafkaTemplate<String, ChatMessageDto> kafkaTemplate;

    @Autowired
    public ChatKafkaProducer(KafkaTemplate<String, ChatMessageDto> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    // assign key for unique // email + # + org-ID or appUserId
    // TODO
    public void sendMessage(ChatMessageDto message) {
        kafkaTemplate.send("chat-messages", message.getSenderUserEmail() , message);
    }
}
