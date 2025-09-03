package com.ayush.chat.dto.request.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReadReceiptDto {
    private String userEmail;
    private List<Long> messageIds;
    private Instant timestamp;
}
