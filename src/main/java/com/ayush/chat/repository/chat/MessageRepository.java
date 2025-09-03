package com.ayush.chat.repository.chat;

import com.ayush.chat.model.User;
import com.ayush.chat.model.chat.Conversation;
import com.ayush.chat.model.chat.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findAllByConversationOrderByCreatedAtAsc(Conversation conversation);
    List<Message> findAllByReceiverAndCreatedAtAfterOrderByCreatedAtAsc(User receiver, LocalDateTime lastSeen);
    List<Message> findAllByReceiverOrderByCreatedAtAsc(User receiver);
    List<Message> findAllByReceiverAndReadFalseOrderByCreatedAtAsc(User receiver);
    List<Message> findAllByReceiverAndSenderAndReadFalseOrderByCreatedAtAsc(User receiver, User sender);
    Page<Message> findAllByConversation(Conversation conversation, Pageable pageable);
}
