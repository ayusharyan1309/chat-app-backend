package com.ayush.chat.model.chat;

import com.ayush.chat.model.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "conversations",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user1_id", "user2_id"}))
public class Conversation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user1_id", nullable = false)
    private User user1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user2_id", nullable = false)
    private User user2;

    @Column(name = "last_message")
    private String lastMessage;

    @Column(name = "last_message_time")
    private Timestamp lastMessageTime;

    @Column(name = "status")
    private String status;

    @Column(name= "created_at")
    private Timestamp createdAt;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

    @Column(name = "isBlockedByUser1")
    private Boolean isBlockedByUser1 = false;

    @Column(name = "isBlockedByUser2")
    private Boolean isBlockedByUser2 = false;
}
