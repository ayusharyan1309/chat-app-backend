package com.ayush.chat.controller.chat;

import com.ayush.chat.dto.request.chat.ChatMessageDto;
import com.ayush.chat.dto.request.chat.ReadReceiptDto;
import com.ayush.chat.dto.request.chat.TypingDto;
import com.ayush.chat.model.User;
import com.ayush.chat.repository.UserRepository;
import com.ayush.chat.repository.chat.ConversationRepository;
import com.ayush.chat.security.ChatPrincipal;
import com.ayush.chat.service.chat.MessageService;
import com.ayush.chat.util.ThreadMemory;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.errors.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;


@Controller
@RequiredArgsConstructor
public class ChatController {
    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);
    @Autowired
    private final MessageService messageService;
    @Autowired
    private final UserRepository userRepository;
    @Autowired
    private final ConversationRepository conversationRepository;


    @MessageMapping("/chat.send")
    public void sendMessage(@Payload ChatMessageDto chatMessageDto, ChatPrincipal principal) {
        if (principal == null) {
            System.err.println("Principal is null in sendMessage!");
            throw new RuntimeException("Principal is null");
        }
        messageService.sendMessage(chatMessageDto,principal);
    }

    @MessageMapping("/chat.typing")
    public void typingIndicator(@Payload TypingDto typingDto, ChatPrincipal principal) {
        if (principal == null) {
            logger.warn("/chat.typing called with null principal");
            return;
        }
       messageService.typingIndicator(typingDto,principal);
    }

    @GetMapping("/chat/unread-messages")
    public ResponseEntity<Map<String, Object>> getUnreadMessages() {

        FirebaseToken firebaseToken = ThreadMemory.getFireBaseTokenData();

        String mobile = (String) firebaseToken.getClaims().get("phone_number");
        String email = firebaseToken.getEmail();
        if(email == null){
//            User appUser = userRepository.findByMobile(mobile);
//            if(appUser!=null)
//                email = appUser.getEmail();

        }

        User user = userRepository.findByEmail(email);
        return messageService.getUnreadMessages(user);
    }

    @PostMapping("/chat/mark-messages-read")
    public ResponseEntity<?> markMessagesRead(@RequestBody List<Long> messageIds, Principal principal, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // --- Robust principal extraction for REST (Bearer token) ---
        if ((principal == null || principal.getName() == null) && authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                FirebaseToken decoded = FirebaseAuth.getInstance().verifyIdToken(token);
                String email = decoded.getEmail();
                principal = new ChatPrincipal(email, "APP", decoded.getUid(), 1L);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (principal == null || messageIds == null || messageIds.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        String userEmail = principal.getName();
        ReadReceiptDto receipt = new ReadReceiptDto(userEmail, messageIds, Instant.now());
//        readReceiptKafkaProducer.sendReadReceipt(receipt);
        return ResponseEntity.ok().build();
    }

    @GetMapping(value = "/chat/conversation/with/{otherUserId}", produces = "application/json")
    public ResponseEntity<?> getConversationWithUserId(@PathVariable Long otherUserId, Principal principal, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // Robust principal extraction for REST (Bearer token)

        Principal finalPrincipal = extractPrincipal(principal, authHeader);
        if (finalPrincipal == null) return ResponseEntity.status(401).body("Unauthorized");
        System.out.println("principal is :" + finalPrincipal);

        Map<String, Object> data = messageService.getConversationWithUserId(finalPrincipal.getName(), otherUserId);
        if (data == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(data);

    }

    @PostMapping("/chat/conversation/{conversationId}/accept")
    public ResponseEntity<?> acceptConversation(@PathVariable Long conversationId, Principal principal, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // Robust principal extraction for REST (Bearer token)
        Principal finalPrincipal = extractPrincipal(principal, authHeader);
        if (finalPrincipal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        try {
            messageService.acceptConversation(conversationId, finalPrincipal);
            return ResponseEntity.ok("Conversation accepted");
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        }

    }


    // --- Fetch paginated (lazy) previous messages for a conversation ---
    @GetMapping("/chat/conversation/{conversationId}/messages")
    public ResponseEntity<?> getPaginatedMessages(@PathVariable Long conversationId,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "40") int size,
                                                  Principal principal,
                                                  @RequestHeader(value = "Authorization", required = false) String authHeader) {

        // Robust principal extraction for REST (Bearer token)
        Principal finalPrincipal = extractPrincipal(principal, authHeader);
        if (finalPrincipal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        try {
            Map<String, Object> paginatedMessages = messageService.getPaginatedMessages(conversationId, finalPrincipal, page, size);
            return ResponseEntity.ok(paginatedMessages);
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(e.getReason());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Something went wrong");
        }
    }


    @PostMapping("/chat/conversation/{conversationId}/block")
    public ResponseEntity<?> blockUser(@PathVariable Long conversationId,
                                     Principal principal,
                                     @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Principal finalPrincipal = extractPrincipal(principal, authHeader);
        if (finalPrincipal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        try {
            messageService.blockUser(conversationId, finalPrincipal);
            return ResponseEntity.ok("User blocked successfully");
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(e.getReason());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Something went wrong");
        }
    }

    @PostMapping("/chat/conversation/{conversationId}/unblock")
    public ResponseEntity<?> unblockUser(@PathVariable Long conversationId,
                                         Principal principal,
                                         @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Principal finalPrincipal = extractPrincipal(principal, authHeader);
        if (finalPrincipal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        try {
            messageService.unblockUser(conversationId, finalPrincipal);
            return ResponseEntity.ok("User unblocked successfully");
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(e.getReason());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("An unexpected error occurred");
        }
    }

    @GetMapping("/chat/friends-chats")
    public ResponseEntity<?> getFriendsChats(Principal principal, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Principal finalPrincipal = extractPrincipal(principal, authHeader);
        if (finalPrincipal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }
        try {
            List<Map<String, Object>> result = messageService.getFriendsChats(finalPrincipal);
            return ResponseEntity.ok(result);
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("An unexpected error occurred");
        }
    }

    private Principal extractPrincipal(Principal principal, String authHeader) {
        if ((principal == null || principal.getName() == null) && authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                FirebaseToken decodedToken = FirebaseAuth.getInstance().verifyIdToken(token);
                String mobile = (String) decodedToken.getClaims().get("phone_number");
                String email = decodedToken.getEmail();
                if(email == null){
//                    EmailUid user = emailUidRepository.findByUid(decodedToken.getUid());
//                    if(user != null) {
//                        email = user.getEmail();
//                    } else {
//                        User appUser = userRepository.findByMobile(mobile);
//                        if(appUser != null)
//                            email = appUser.getEmail();
//                    }
                }
                return new ChatPrincipal(email, "APP", decodedToken.getUid(), 1L);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
        return principal;
    }
}
