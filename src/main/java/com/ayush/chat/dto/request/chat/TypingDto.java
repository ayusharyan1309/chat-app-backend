package com.ayush.chat.dto.request.chat;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TypingDto {
    private String senderUsername;
    private String recipientUsername;
    private boolean typing;

    public TypingDto(String senderUsername, String recipientUsername, boolean typing) {
        this.senderUsername = senderUsername;
        this.recipientUsername = recipientUsername;
        this.typing = typing;
    }
}
