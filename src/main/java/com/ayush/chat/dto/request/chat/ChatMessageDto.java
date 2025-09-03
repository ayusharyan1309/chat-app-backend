package com.ayush.chat.dto.request.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.NotBlank;

@NoArgsConstructor
@Data
@AllArgsConstructor
public class ChatMessageDto {
    @NotBlank
    private String recipientEmail;
    @NotBlank
    private String content;
    private String senderUserEmail;
    private Long messageId;
}
