package com.ayush.chat.service.chat;

import com.ayush.chat.dto.request.chat.ChatMessageDto;
import com.ayush.chat.dto.request.chat.TypingDto;
import com.ayush.chat.model.User;
import com.ayush.chat.model.chat.Conversation;
import com.ayush.chat.model.chat.Message;
import com.ayush.chat.security.ChatPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;

import java.security.Principal;
import java.util.List;
import java.util.Map;

public interface MessageService {

    public void sendMessage(ChatMessageDto chatMessageDto, ChatPrincipal principal);

    public Message saveMessage(User sender, User receiver, String messageContent);

    public void typingIndicator(TypingDto typingDto, ChatPrincipal principal);

    public ResponseEntity<Map<String, Object>> getUnreadMessages(User user);

    public List<Message> getUnreadMessagesForUser(User receiver);

    public void markMessagesAsRead(List<Long> messageIds);

    public Page<Message> findMessagesByConversation(Conversation conversation, Pageable pageable);

    public Map<String,Object> getConversationWithUserId(String user1, Long user2);

    public void  acceptConversation(@PathVariable Long conversationId,Principal finalPrincipal );

    public  Map<String, Object> getPaginatedMessages(Long conversationId, Principal principal , int page, int size);

    public void blockUser(Long conversationId, Principal principal);

    public void unblockUser(@PathVariable Long conversationId,
                                         Principal principal);

    public List<Map<String, Object>> getFriendsChats(Principal principal);
}
