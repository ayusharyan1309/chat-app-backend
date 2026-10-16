package com.ayush.chat.chat;

import com.ayush.chat.auth.Principals.ChatUserPrincipal;
import com.ayush.chat.chat.event.ChatEvent;
import com.ayush.chat.core.cluster.ClusterService;
import com.ayush.chat.core.connector.runtime.PlatformConnectors;
import com.ayush.chat.core.connector.runtime.TenantConnectors;
import com.ayush.chat.core.util.Json;
import com.ayush.chat.tenant.Feature;
import com.ayush.chat.tenant.TenantDirectory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Online status. A user is online while some node holds a {@code presence:<tenant>:<user>} key,
 * which that node refreshes for its connected users. Clients watch the users they care about
 * (e.g. the open chat) and receive {@code presence} events when they come and go.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final int MAX_WATCHED = 500;

    private final PlatformConnectors infrastructure;
    private final TenantConnectors connectors;
    private final ClusterService cluster;
    private final RealtimeGateway gateway;
    private final TenantDirectory tenants;

    /** userKey -> local session ids */
    private final Map<String, Set<String>> localSessions = new ConcurrentHashMap<>();
    /** session id -> principal */
    private final Map<String, ChatUserPrincipal> sessionPrincipals = new ConcurrentHashMap<>();
    /** session id -> watched user keys */
    private final Map<String, Set<String>> watches = new ConcurrentHashMap<>();
    /** watched user key -> watching session ids */
    private final Map<String, Set<String>> watchers = new ConcurrentHashMap<>();

    public record PresenceView(String userId, boolean online, Instant lastSeenAt) {
    }

    record PresenceChange(String tenant, String userId, boolean online, Instant lastSeenAt) {
    }

    @PostConstruct
    void subscribe() {
        infrastructure.busSubscriptions().subscribe(gateway.topic("presence"), null, this::onPresenceChange);
    }

    // ------------------------------------------------------------------ session lifecycle

    public void sessionOpened(String sessionId, ChatUserPrincipal principal) {
        sessionPrincipals.put(sessionId, principal);
        String key = principal.getName();
        boolean first;
        synchronized (localSessions) {
            Set<String> sessions = localSessions.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
            first = sessions.isEmpty();
            sessions.add(sessionId);
        }
        if (first) {
            boolean wasOnline = isOnline(principal.tenant(), principal.userId());
            infrastructure.cache().set(presenceKey(principal.tenant(), principal.userId()), cluster.getNodeId(), TTL);
            if (!wasOnline) {
                broadcast(new PresenceChange(principal.tenant(), principal.userId(), true, null));
            }
        }
    }

    public void sessionClosed(String sessionId) {
        ChatUserPrincipal principal = sessionPrincipals.remove(sessionId);
        unwatchAll(sessionId);
        if (principal == null) {
            return;
        }
        String key = principal.getName();
        boolean last;
        synchronized (localSessions) {
            Set<String> sessions = localSessions.get(key);
            if (sessions != null) {
                sessions.remove(sessionId);
            }
            last = sessions == null || sessions.isEmpty();
            if (last) {
                localSessions.remove(key);
            }
        }
        if (!last) {
            return;
        }
        Instant now = Instant.now();
        try {
            connectors.store(principal.tenant()).updateLastSeen(principal.tenant(), principal.userId(), now);
        } catch (Exception e) {
            log.debug("Could not record last seen for {}: {}", key, e.getMessage());
        }
        String presenceKey = presenceKey(principal.tenant(), principal.userId());
        infrastructure.cache().deleteIfEquals(presenceKey, cluster.getNodeId());
        if (infrastructure.cache().get(presenceKey).isEmpty()) {
            broadcast(new PresenceChange(principal.tenant(), principal.userId(), false, now));
        }
    }

    /** Keep presence keys alive for everyone connected to this node. */
    @Scheduled(fixedDelay = 20_000, initialDelay = 20_000)
    public void heartbeat() {
        for (String key : List.copyOf(localSessions.keySet())) {
            int idx = key.indexOf(ChatUserPrincipal.SEPARATOR);
            try {
                infrastructure.cache().set(presenceKey(key.substring(0, idx), key.substring(idx + 2)), cluster.getNodeId(), TTL);
            } catch (Exception e) {
                log.debug("Presence heartbeat failed: {}", e.getMessage());
                return;
            }
        }
    }

    // ------------------------------------------------------------------ queries

    public boolean isOnline(String tenant, String userId) {
        return infrastructure.cache().get(presenceKey(tenant, userId)).isPresent();
    }

    public Map<String, Boolean> onlineMany(String tenant, Collection<String> userIds) {
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(userIds));
        List<String> keys = ids.stream().map(id -> presenceKey(tenant, id)).toList();
        List<String> values = keys.isEmpty() ? List.of() : infrastructure.cache().getMany(keys);
        Map<String, Boolean> result = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            result.put(ids.get(i), values.get(i) != null);
        }
        return result;
    }

    // ------------------------------------------------------------------ watching

    /** Replace the watch list of a session and send the current state of every watched user. */
    public void watch(String sessionId, ChatUserPrincipal principal, Collection<String> userIds) {
        unwatchAll(sessionId);
        if (!tenants.get(principal.tenant()).has(Feature.PRESENCE)) {
            return;
        }
        List<String> limited = userIds.stream().filter(id -> id != null && !id.isBlank()).distinct().limit(MAX_WATCHED).toList();
        Set<String> keys = ConcurrentHashMap.newKeySet();
        for (String id : limited) {
            String key = ChatUserPrincipal.key(principal.tenant(), id);
            keys.add(key);
            watchers.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
        }
        watches.put(sessionId, keys);
        Map<String, Boolean> online = onlineMany(principal.tenant(), limited);
        Map<String, Instant> lastSeen = new HashMap<>();
        connectors.store(principal.tenant()).findUsers(principal.tenant(), limited)
                .forEach(u -> lastSeen.put(u.getId(), u.getLastSeenAt()));
        for (String id : limited) {
            gateway.deliverLocally(principal.tenant(), principal.userId(), ChatEvent.of(ChatEvent.PRESENCE, null,
                    new PresenceView(id, online.getOrDefault(id, false), lastSeen.get(id))));
        }
    }

    private void unwatchAll(String sessionId) {
        Set<String> keys = watches.remove(sessionId);
        if (keys == null) {
            return;
        }
        for (String key : keys) {
            watchers.computeIfPresent(key, (k, sessions) -> {
                sessions.remove(sessionId);
                return sessions.isEmpty() ? null : sessions;
            });
        }
    }

    private void broadcast(PresenceChange change) {
        try {
            infrastructure.bus().publish(gateway.topic("presence"), change.tenant(), Json.write(change));
        } catch (Exception e) {
            log.debug("Presence broadcast failed: {}", e.getMessage());
        }
    }

    private void onPresenceChange(String payload) {
        PresenceChange change = Json.read(payload, PresenceChange.class);
        Set<String> sessions = watchers.get(ChatUserPrincipal.key(change.tenant(), change.userId()));
        if (sessions == null) {
            return;
        }
        ChatEvent event = ChatEvent.of(ChatEvent.PRESENCE, null,
                new PresenceView(change.userId(), change.online(), change.lastSeenAt()));
        Set<String> notified = new LinkedHashSet<>();
        for (String sessionId : sessions) {
            ChatUserPrincipal watcher = sessionPrincipals.get(sessionId);
            if (watcher != null && notified.add(watcher.getName())) {
                gateway.deliverLocally(watcher.tenant(), watcher.userId(), event);
            }
        }
    }

    private static String presenceKey(String tenant, String userId) {
        return "presence:" + tenant + ":" + userId;
    }
}
