package com.ayush.chat.chat.dto.response;

import com.ayush.chat.store.model.Conversation;
import com.ayush.chat.store.model.Participant;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ConversationView(String id, Conversation.Type type, String title, String description, String avatarUrl,
                               String createdBy, Instant createdAt, long lastSeq, LastMessage lastMessage,
                               long unreadCount, boolean muted, boolean archived, boolean pinned,
                               Participant.Role role, UserView peer, List<ParticipantView> participants) {
}
