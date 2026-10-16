package com.ayush.chat.chat.ws;

import com.ayush.chat.auth.Principals;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raw WebSocket sessions on this node, so the platform can force-close them (tenant suspended,
 * user deactivated).
 */
@Slf4j
@Component
public class SessionRegistry {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    void register(WebSocketSession session) {
        sessions.put(session.getId(), session);
    }

    void unregister(WebSocketSession session) {
        sessions.remove(session.getId());
    }

    public int closeTenant(String tenant, String reason) {
        return closeWhere(p -> p.tenant().equals(tenant), reason);
    }

    public int closeUser(String tenant, String userId, String reason) {
        return closeWhere(p -> p.tenant().equals(tenant) && p.userId().equals(userId), reason);
    }

    private int closeWhere(java.util.function.Predicate<Principals.ChatUserPrincipal> match, String reason) {
        int closed = 0;
        for (WebSocketSession session : sessions.values()) {
            if (session.getPrincipal() instanceof Principals.ChatUserPrincipal p && match.test(p)) {
                try {
                    session.close(CloseStatus.POLICY_VIOLATION.withReason(reason));
                    closed++;
                } catch (IOException e) {
                    log.debug("Closing session {} failed: {}", session.getId(), e.getMessage());
                }
            }
        }
        return closed;
    }
}
