package com.ayush.chat.chat.dto.request;

import java.util.List;

public record CreateGroupRequest(String title, List<String> members, String description, String avatarUrl) {
}
