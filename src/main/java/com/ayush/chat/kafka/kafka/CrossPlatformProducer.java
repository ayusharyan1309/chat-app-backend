package com.ayush.chat.kafka.kafka;

import com.ayush.chat.dto.request.chat.ChatMessageDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;


@Service
public class CrossPlatformProducer {

        private final KafkaTemplate<String, ChatMessageDto> kafkaTemplateCrossMessaging;

        @Autowired
        public CrossPlatformProducer(KafkaTemplate<String, ChatMessageDto> kafkaTemplateCrossMessaging) {
            this.kafkaTemplateCrossMessaging = kafkaTemplateCrossMessaging;
        }

        // assign key for unique // email + # + org-ID or appUserId
        // TODO
        public void sendCrossPlatformMessage(ChatMessageDto message) {

            kafkaTemplateCrossMessaging.send("cross-messages",message.getSenderUserEmail(), message);
        }
}
