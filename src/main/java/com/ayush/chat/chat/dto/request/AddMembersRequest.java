package com.ayush.chat.chat.dto.request;

import java.util.List;

public record AddMembersRequest(List<String> userIds) {
}
