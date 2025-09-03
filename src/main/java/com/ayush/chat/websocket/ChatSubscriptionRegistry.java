package com.ayush.chat.websocket;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChatSubscriptionRegistry {
    // recipientEmail -> set of chatWithEmail (senders whose chat is open on recipient's UI)
    private final Map<String, Set<String>> activeChats = new ConcurrentHashMap<>();

    public void subscribe(String recipientEmail, String chatWithEmail) {
        activeChats.computeIfAbsent(recipientEmail, k -> ConcurrentHashMap.newKeySet()).add(chatWithEmail);
    }

    public void unsubscribe(String recipientEmail, String chatWithEmail) {
        Set<String> chats = activeChats.get(recipientEmail);
        if (chats != null) {
            chats.remove(chatWithEmail);
            if (chats.isEmpty()) {
                activeChats.remove(recipientEmail);
            }
        }
    }

    public boolean isChatOpen(String recipientEmail, String chatWithEmail) {
        Set<String> chats = activeChats.get(recipientEmail);
        return chats != null && chats.contains(chatWithEmail);
    }

    public void removeAllForRecipient(String recipientEmail) {
        activeChats.remove(recipientEmail);
    }
}
