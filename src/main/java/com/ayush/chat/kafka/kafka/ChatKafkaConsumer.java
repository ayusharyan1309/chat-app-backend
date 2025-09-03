package com.ayush.chat.kafka.kafka;

import com.ayush.chat.dto.request.chat.ChatMessageDto;
import com.ayush.chat.model.User;
import com.ayush.chat.model.chat.Message;
import com.ayush.chat.repository.UserRepository;
import com.ayush.chat.service.chat.MessageServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatKafkaConsumer {
    @Autowired
    private MessageServiceImpl messageServiceImpl;
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CrossPlatformProducer crossPlatformProducer;

    @KafkaListener(topics = "chat-messages", groupId = "chat-group")
    @Transactional
    public void consume(ChatMessageDto chatMessageDto) {
        User sender = userRepository.findByEmail(chatMessageDto.getSenderUserEmail());
        User recipient = userRepository.findByEmail(chatMessageDto.getRecipientEmail());
        if (sender == null || recipient == null) return;
        Message message = messageServiceImpl.saveMessage(sender, recipient, chatMessageDto.getContent());
        chatMessageDto.setMessageId(message.getId());

            messagingTemplate.convertAndSendToUser(
                    chatMessageDto.getRecipientEmail(),
                    "/queue/messages",
                    chatMessageDto
            );

    }
}
