package com.ayush.chat.repository.chat;

import com.ayush.chat.model.User;
import com.ayush.chat.model.chat.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    @Query("SELECT c FROM Conversation c WHERE (c.user1 = :user1 AND c.user2 = :user2) OR (c.user1 = :user2 AND c.user2 = :user1)")
    Conversation findByUserPair(@Param("user1") User user1, @Param("user2") User user2);
}
