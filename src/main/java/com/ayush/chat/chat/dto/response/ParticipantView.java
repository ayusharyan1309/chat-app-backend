package com.ayush.chat.chat.dto.response;

import com.ayush.chat.store.model.Participant;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParticipantView(String userId, Participant.Role role, String displayName, String avatarUrl,
                              long deliveredSeq, long readSeq, Instant joinedAt) {
}
