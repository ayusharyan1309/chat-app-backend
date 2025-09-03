package com.ayush.chat.service.chat;

import com.ayush.chat.model.User;
import com.ayush.chat.model.chat.Conversation;
import com.ayush.chat.model.chat.Message;
import com.ayush.chat.repository.UserRepository;
import com.ayush.chat.repository.chat.ConversationRepository;
import com.ayush.chat.repository.chat.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MessageService {
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;

    public Message sendMessage(User sender, User receiver, String messageContent) {
        // Find or create a conversation
        Conversation conversation = conversationRepository.findByUserPair(sender, receiver);
        boolean isNewConversation = false;
        if (conversation == null) {
            conversation = new Conversation();
            conversation.setUser1(sender);
            conversation.setUser2(receiver);
            conversation.setCreatedAt(new Timestamp(System.currentTimeMillis()));
            conversation.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            conversation.setStatus("INIT");
            conversation = conversationRepository.save(conversation);
            isNewConversation = true;
        }
        // If conversation is INACTIVE and sender is blocked, do not allow
        if ("INACTIVE".equals(conversation.getStatus()) && sender.getEmail().equals(conversation.getUser1().getEmail())) {
            throw new RuntimeException("You are blocked by the recipient.");
        }
        // If conversation is INIT and recipient sends a message, activate
        if ("INIT".equals(conversation.getStatus()) && receiver.getEmail().equals(conversation.getUser1().getEmail())) {
            conversation.setStatus("ACTIVE");
            conversation.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            conversationRepository.save(conversation);
        }
        Message message = Message.builder()
                .conversation(conversation)
                .sender(sender)
                .receiver(receiver)
                .message(messageContent)
                .createdAt(new Timestamp(System.currentTimeMillis()))
                .updatedAt(new Timestamp(System.currentTimeMillis()))
                .status(true)
                .build();
        Message savedMessage = messageRepository.save(message);
        // Optionally update last message fields
        conversation.setLastMessage(messageContent);
        conversation.setLastMessageTime(savedMessage.getCreatedAt());
        conversation.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        conversationRepository.save(conversation);
        return savedMessage;
    }

    public List<Message> getConversationMessages(Conversation conversation) {
        // Implement if needed: fetch all messages for a conversation
        return messageRepository.findAllByConversationOrderByCreatedAtAsc(conversation);
    }

    public List<Message> getUnreadMessagesForUser(User receiver, LocalDateTime lastSeen) {
        if (lastSeen == null) {
            // Fetch all messages for this user
            return messageRepository.findAllByReceiverOrderByCreatedAtAsc(receiver);
        } else {
            return messageRepository.findAllByReceiverAndCreatedAtAfterOrderByCreatedAtAsc(receiver, lastSeen);
        }
    }

    public List<Message> getUnreadMessagesForUser(User receiver) {
        return messageRepository.findAllByReceiverAndReadFalseOrderByCreatedAtAsc(receiver);
    }

    public List<Message> getUnreadMessagesFromSender(User receiver, User sender) {
        return messageRepository.findAllByReceiverAndSenderAndReadFalseOrderByCreatedAtAsc(receiver, sender);
    }

    public void markMessagesAsRead(List<Long> messageIds) {
        List<Message> messages = messageRepository.findAllById(messageIds);
        for (Message msg : messages) {
            msg.setRead(true);
            msg.setReadAt(new Timestamp(System.currentTimeMillis()));
        }
        messageRepository.saveAll(messages);
    }

    public Message saveMessage(Message message) {
        return messageRepository.save(message);
    }

    // Fetch paginated messages for a conversation (for lazy loading)
    public Page<Message> findMessagesByConversation(Conversation conversation, Pageable pageable) {
        return messageRepository.findAllByConversation(conversation, pageable);
    }
}
