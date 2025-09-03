package com.ayush.chat.service.chat;

import com.ayush.chat.dto.request.chat.ChatMessageDto;
import com.ayush.chat.dto.request.chat.TypingDto;
import com.ayush.chat.dto.response.chat.MessageDto;
import com.ayush.chat.kafka.kafka.ChatKafkaProducer;
import com.ayush.chat.kafka.kafka.CrossPlatformProducer;
import com.ayush.chat.model.User;
import com.ayush.chat.model.chat.Conversation;
import com.ayush.chat.model.chat.Message;
import com.ayush.chat.repository.UserRepository;
import com.ayush.chat.repository.chat.ConversationRepository;
import com.ayush.chat.repository.chat.MessageRepository;
import com.ayush.chat.security.ChatPrincipal;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.errors.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MessageServiceImpl implements MessageService {

    @Autowired
    private final MessageRepository messageRepository;

    @Autowired
    private final UserRepository userRepository;

    @Autowired
    private final ConversationRepository conversationRepository;

    @Autowired
    private final ChatKafkaProducer chatKafkaProducer;

    @Autowired
    private final SimpMessagingTemplate messagingTemplate;

    @Autowired
    private final CrossPlatformProducer crossPlatformProducer;

    private static final Logger logger = LoggerFactory.getLogger(MessageServiceImpl.class);

    @Override
    public void sendMessage(ChatMessageDto chatMessageDto, ChatPrincipal principal) {
        User sender = userRepository.findByUId(principal.getUserId());
        if(sender == null) {
            System.out.println("sender is null here");
            return;
        }
        User recipient = userRepository.findByEmail(chatMessageDto.getRecipientEmail());
        if(recipient == null) {
            System.out.println("recipient is null here");
            return;
        }
        // Check if conversation exists and if either user has blocked the other
        Conversation conv = conversationRepository.findByUserPair(sender, recipient);
        if (conv != null) {
            boolean isSenderUser1 = sender.getId().equals(conv.getUser1().getId());
            boolean isBlocked = (isSenderUser1 && conv.getIsBlockedByUser2()) || (!isSenderUser1 && conv.getIsBlockedByUser1());
            if (isBlocked) {
                System.out.println("Message blocked - sender is blocked by recipient");
                return;
            }
        }
        // Set sender email for Kafka consumer
        chatMessageDto.setSenderUserEmail(sender.getEmail());
        // Push message to Kafka for async processing
        if(!chatMessageDto.isCrossPlatformMessage()) {
            chatKafkaProducer.sendMessage(chatMessageDto);
        }
        else {
            crossPlatformProducer.sendCrossPlatformMessage(chatMessageDto);
        }

        // Do not save or notify here. Consumer will handle DB save and notification.
    }

    @Override
    public Message saveMessage(User sender, User receiver, String messageContent) {
        // Find or create a conversation
        System.out.println("sender is : " + sender + " receober is " + receiver);

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


    @Override
    public void typingIndicator(TypingDto typingDto, ChatPrincipal principal) {
        String senderUserId = principal.getUserId();
        User senderUser = userRepository.findByUId(senderUserId);

        typingDto.setSenderUsername(senderUser.getEmail());

        logger.info("Received typing event: senderUserId={}, recipientId={}, typing={}", senderUserId, typingDto.getRecipientUsername(), typingDto.isTyping());
        // Forward typing status to the recipient only
        messagingTemplate.convertAndSendToUser(
                typingDto.getRecipientUsername(),
                "/queue/typing",
                typingDto
        );
        logger.info("Forwarded typing event to recipient: {}", typingDto.getRecipientUsername());
    }

    @Override
    public ResponseEntity<Map<String, Object>> getUnreadMessages(User user) {
        List<Message> unread = getUnreadMessagesForUser(user);
        // Group messages by sender and include conversation info
        Map<String, Object> result = new LinkedHashMap<>();
        unread.stream().collect(Collectors.groupingBy(
                msg -> msg.getSender() != null ? msg.getSender().getEmail() : "unknown"
        )).forEach((senderEmail, msgs) -> {
            if (msgs.isEmpty()) return;
            Message first = msgs.get(0);
            Conversation conv = first.getConversation();
            // Compose messages list
            List<Map<String, Object>> messages = msgs.stream().map(msg -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("messageId", msg.getId());
                map.put("message", msg.getMessage());
                map.put("senderUserEmail", msg.getSender() != null ? msg.getSender().getEmail() : null);
                map.put("recipientEmail", msg.getReceiver() != null ? msg.getReceiver().getEmail() : null);
                map.put("createdAt", msg.getCreatedAt());
                return map;
            }).collect(Collectors.toList());
            // Compose conversation info
            Map<String, Object> convInfo = new LinkedHashMap<>();
            convInfo.put("conversationId", conv.getId());
            convInfo.put("status", conv.getStatus());
            convInfo.put("user1Email", conv.getUser1() != null ? conv.getUser1().getEmail() : null);
            convInfo.put("user2Email", conv.getUser2() != null ? conv.getUser2().getEmail() : null);
            convInfo.put("messages", messages);
            result.put(senderEmail, convInfo);
        });
        return ResponseEntity.ok(result);
    }

    @Override
    public List<Message> getUnreadMessagesForUser(User receiver) {
        return messageRepository.findAllByReceiverAndReadFalseOrderByCreatedAtAsc(receiver);
    }

    @Override
    public void markMessagesAsRead(List<Long> messageIds) {
        List<Message> messages = messageRepository.findAllById(messageIds);
        for (Message msg : messages) {
            msg.setRead(true);
            msg.setReadAt(new Timestamp(System.currentTimeMillis()));
        }
        messageRepository.saveAll(messages);
    }

    // Fetch paginated messages for a conversation (for lazy loading)
    @Override
    public Page<Message> findMessagesByConversation(Conversation conversation, Pageable pageable) {
        return messageRepository.findAllByConversation(conversation, pageable);
    }

    @Override
    public Map<String,Object> getConversationWithUserId(String user1Email , Long otherId) {

        User user1 = userRepository.findByEmail(user1Email);
        User user2 = userRepository.findById(otherId);

        if (user1 == null || user2 == null) {
            return null;
        }
        Conversation conv = conversationRepository.findByUserPair(user1, user2);

        if (conv == null) {
            return null;
        }

        Map<String, Object> convInfo = new LinkedHashMap<>();
        convInfo.put("id", conv.getId());
        convInfo.put("conversationId", conv.getId());
        convInfo.put("status", conv.getStatus());
        convInfo.put("user1Id", conv.getUser1() != null ? conv.getUser1().getId() : null);
        convInfo.put("user2Id", conv.getUser2() != null ? conv.getUser2().getId() : null);
        convInfo.put("isBlockedByUser1", conv.getIsBlockedByUser1());
        convInfo.put("isBlockedByUser2", conv.getIsBlockedByUser2());
        return convInfo;
    }

    @Override
    public void acceptConversation(Long conversationId, Principal finalPrincipal ) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));

        // Only recipient (user2) can accept
        if (finalPrincipal.getName() == null ||
                !finalPrincipal.getName().equals(conversation.getUser2().getEmail())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the recipient can accept the conversation");
        }

        conversation.setStatus("ACTIVE");
        conversation.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        conversationRepository.save(conversation);
    }

    @Override
    public Map<String, Object> getPaginatedMessages(Long conversationId, Principal principal, int page, int size) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));

        String userEmail = principal.getName();
        if (userEmail == null || (!userEmail.equals(conversation.getUser1().getEmail())
                && !userEmail.equals(conversation.getUser2().getEmail()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a participant can view messages");
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Message> messagePage = findMessagesByConversation(conversation, pageable);

        List<MessageDto> messageDtos = messagePage.getContent()
                .stream()
                .map(MessageDto::fromEntity)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messages", messageDtos);
        result.put("hasMore", messagePage.hasNext());
        result.put("total", messagePage.getTotalElements());
        return result;
    }

    @Override
    public void blockUser(Long conversationId, Principal principal) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));

        String userEmail = principal.getName();
        boolean isUser1 = userEmail.equals(conversation.getUser1().getEmail());
        boolean isUser2 = userEmail.equals(conversation.getUser2().getEmail());

        if (!isUser1 && !isUser2) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only conversation participants can block");
        }

        if (isUser1) {
            conversation.setIsBlockedByUser1(true);
        } else {
            conversation.setIsBlockedByUser2(true);
        }

        // Set status to INACTIVE if either participant blocks
        if (Boolean.TRUE.equals(conversation.getIsBlockedByUser1()) || Boolean.TRUE.equals(conversation.getIsBlockedByUser2())) {
            conversation.setStatus("INACTIVE");
        }

        conversation.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        conversationRepository.save(conversation);
    }

    @Override
    public void unblockUser(Long conversationId, Principal principal) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));

        String userEmail = principal.getName();
        boolean isUser1 = userEmail.equals(conversation.getUser1().getEmail());
        boolean isUser2 = userEmail.equals(conversation.getUser2().getEmail());

        if (!isUser1 && !isUser2) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only conversation participants can unblock");
        }

        if (isUser1) {
            conversation.setIsBlockedByUser1(false);
        } else {
            conversation.setIsBlockedByUser2(false);
        }

        // If neither user has blocked, set conversation to ACTIVE
        if (!Boolean.TRUE.equals(conversation.getIsBlockedByUser1()) &&
                !Boolean.TRUE.equals(conversation.getIsBlockedByUser2())) {
            conversation.setStatus("ACTIVE");
        }

        conversation.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        conversationRepository.save(conversation);
    }

    @Override
    public List<Map<String, Object>> getFriendsChats(Principal principal) {
        String userEmail = principal.getName();
        User user = userRepository.findByEmail(userEmail);
        if (user == null) {
            throw new ResourceNotFoundException("User not found");
        }

        List<Conversation> conversations = conversationRepository.findAllByUser(user);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Conversation c : conversations) {
            User other = c.getUser1().getId().equals(user.getId()) ? c.getUser2() : c.getUser1();

            Map<String, Object> dto = new HashMap<>();
            dto.put("conversationId", c.getId());
            dto.put("userEmail", other.getEmail());
            dto.put("userName", other.getFullName());
            dto.put("lastMessage", c.getLastMessage());
            dto.put("lastMessageTime", c.getLastMessageTime());
            result.add(dto);
        }
        return result;
    }

}
