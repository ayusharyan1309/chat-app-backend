package com.ayush.chat.chat.dto.socket;

import java.util.List;

public record PresenceWatchFrame(List<String> userIds) {
}
