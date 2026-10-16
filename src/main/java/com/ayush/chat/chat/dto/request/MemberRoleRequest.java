package com.ayush.chat.chat.dto.request;

import com.ayush.chat.store.model.Participant;

public record MemberRoleRequest(Participant.Role role) {
}
