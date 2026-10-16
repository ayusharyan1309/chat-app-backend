package com.ayush.chat.chat.websocket;

import com.ayush.chat.auth.security.ActiveUserCheck;
import com.ayush.chat.auth.security.Principals.ChatUserPrincipal;
import com.ayush.chat.auth.service.JwtService;
import com.ayush.chat.core.cluster.ClusterService;
import com.ayush.chat.core.exception.DomainException;
import com.ayush.chat.tenant.model.TenantSnapshot;
import com.ayush.chat.tenant.service.TenantDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Authenticates STOMP CONNECT frames and locks down what clients may do afterwards:
 * only {@code /user/queue/events} may be subscribed, only {@code /app/**} may be sent to, and every
 * frame needs a user.
 */
@Component
@RequiredArgsConstructor
public class StompAuthInterceptor implements ChannelInterceptor {

    private final JwtService jwt;
    private final TenantDirectory tenants;
    private final ClusterService cluster;
    private final ActiveUserCheck users;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        try {
            switch (command) {
                case CONNECT, STOMP -> accessor.setUser(authenticate(accessor));
                case SUBSCRIBE -> {
                    requireUser(accessor);
                    String destination = accessor.getDestination();
                    if (destination == null || !destination.equals("/user/queue/events")) {
                        throw new MessageDeliveryException("Only /user/queue/events can be subscribed");
                    }
                }
                case SEND -> {
                    requireUser(accessor);
                    // Clients may only address the application, never broker or user destinations:
                    // SEND /user/<someone>/queue/events would otherwise inject forged events.
                    String destination = accessor.getDestination();
                    if (destination == null || !destination.startsWith("/app/")) {
                        throw new MessageDeliveryException("Clients may only send to /app/** destinations");
                    }
                }
                default -> {
                }
            }
        } catch (DomainException e) {
            throw new MessageDeliveryException(e.getMessage());
        }
        return message;
    }

    private ChatUserPrincipal authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null) {
            header = accessor.getFirstNativeHeader("authorization");
        }
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7).trim()
                : accessor.getFirstNativeHeader("token");
        if (token == null || token.isBlank()) {
            throw DomainException.unauthorized("Missing Authorization header on CONNECT");
        }
        if (!(jwt.verify(token) instanceof ChatUserPrincipal principal)) {
            throw DomainException.unauthorized("A user token is required");
        }
        TenantSnapshot tenant = tenants.requireUsable(principal.tenant());
        users.requireActiveUser(principal.tenant(), principal.userId());
        Long max = tenant.limits().maxConnections();
        if (max != null && cluster.connections(tenant.slug()) >= max) {
            throw DomainException.quotaExceeded("Connection limit of " + max + " reached for this tenant");
        }
        return principal;
    }

    private static void requireUser(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof ChatUserPrincipal)) {
            throw new MessageDeliveryException("Not authenticated");
        }
    }
}
