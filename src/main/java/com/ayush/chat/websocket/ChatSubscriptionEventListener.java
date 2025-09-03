package com.ayush.chat.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

@Component
@RequiredArgsConstructor
public class ChatSubscriptionEventListener {
    private final ChatSubscriptionRegistry registry;

    @EventListener
    public void handleSubscribeEvent(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        String recipientEmail = getUserEmail(accessor);
        // Since frontend subscribes to /user/queue/messages, use a header to get chatWithEmail
        String chatWithEmail = accessor.getFirstNativeHeader("chatWithEmail");
        if (recipientEmail != null && chatWithEmail != null) {
            registry.subscribe(recipientEmail, chatWithEmail);
        }
    }

    @EventListener
    public void handleUnsubscribeEvent(SessionUnsubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        String recipientEmail = getUserEmail(accessor);
        // Since frontend subscribes to /user/queue/messages, use a header to get chatWithEmail
        String chatWithEmail = accessor.getFirstNativeHeader("chatWithEmail");
        if (recipientEmail != null && chatWithEmail != null) {
            registry.unsubscribe(recipientEmail, chatWithEmail);
        }
    }

    @EventListener
    public void handleDisconnectEvent(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String recipientEmail = getUserEmail(accessor);
        if (recipientEmail != null) {
            registry.removeAllForRecipient(recipientEmail);
        }
    }

    private String getUserEmail(StompHeaderAccessor accessor) {
        if (accessor.getUser() != null) {
            return accessor.getUser().getName();
        }
        return null;
    }
}
