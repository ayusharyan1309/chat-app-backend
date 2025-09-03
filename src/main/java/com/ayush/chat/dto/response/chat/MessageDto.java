package com.ayush.chat.dto.response.chat;

import com.ayush.chat.model.chat.Message;

import java.sql.Timestamp;

public class MessageDto {
    private Long messageId;
    private String message;
    private String senderUserEmail;
    private String recipientEmail;
    private Timestamp createdAt;

    public MessageDto() {}

    public MessageDto(Long messageId, String message, String senderUserEmail, String recipientEmail, Timestamp createdAt) {
        this.messageId = messageId;
        this.message = message;
        this.senderUserEmail = senderUserEmail;
        this.recipientEmail = recipientEmail;
        this.createdAt = createdAt;
    }

    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getSenderUserEmail() { return senderUserEmail; }
    public void setSenderUserEmail(String senderUserEmail) { this.senderUserEmail = senderUserEmail; }

    public String getRecipientEmail() { return recipientEmail; }
    public void setRecipientEmail(String recipientEmail) { this.recipientEmail = recipientEmail; }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }

    // Factory method to convert from Message entity
    public static MessageDto fromEntity(Message msg) {
        if (msg == null) return null;
        return new MessageDto(
                msg.getId(),
                msg.getMessage(),
                msg.getSender() != null ? msg.getSender().getEmail() : null,
                msg.getReceiver() != null ? msg.getReceiver().getEmail() : null,
                msg.getCreatedAt()
        );
    }
}
