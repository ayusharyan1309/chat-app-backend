package com.ayush.chat.chat.ws;

import com.ayush.chat.auth.Principals.ChatUserPrincipal;
import com.ayush.chat.chat.PresenceService;
import com.ayush.chat.core.cluster.ClusterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wires STOMP session lifecycle into presence and cluster connection accounting.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionEvents {

    private final PresenceService presence;
    private final ClusterService cluster;
    private final Map<String, String> sessionTenants = new ConcurrentHashMap<>();

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        if (!(event.getUser() instanceof ChatUserPrincipal principal)) {
            return;
        }
        String sessionId = (String) event.getMessage().getHeaders().get("simpSessionId");
        if (sessionId == null) {
            return;
        }
        sessionTenants.put(sessionId, principal.tenant());
        cluster.connectionOpened(principal.tenant());
        try {
            presence.sessionOpened(sessionId, principal);
        } catch (Exception e) {
            log.warn("Presence update failed for {}: {}", principal.getName(), e.getMessage());
        }
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        String tenant = sessionTenants.remove(event.getSessionId());
        if (tenant == null) {
            return;
        }
        cluster.connectionClosed(tenant);
        try {
            presence.sessionClosed(event.getSessionId());
        } catch (Exception e) {
            log.warn("Presence cleanup failed for session {}: {}", event.getSessionId(), e.getMessage());
        }
    }
}
