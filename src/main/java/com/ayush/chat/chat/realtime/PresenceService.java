package com.ayush.chat.chat.realtime;

import com.ayush.chat.auth.security.Principals.ChatUserPrincipal;
import com.ayush.chat.cache.CacheKeys;
import com.ayush.chat.chat.dto.response.PresenceView;
import com.ayush.chat.chat.event.ChatEvent;
import com.ayush.chat.chat.port.PresenceQuery;
import com.ayush.chat.core.cluster.BusTopic;
import com.ayush.chat.core.cluster.ClusterBus;
import com.ayush.chat.core.cluster.ClusterService;
import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.connector.runtime.PlatformConnectors;
import com.ayush.chat.core.connector.runtime.TenantConnectors;
import com.ayush.chat.tenant.model.Feature;
import com.ayush.chat.tenant.service.TenantDirectory;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
 * Online status. A user is online while some node holds their presence key, which that node
 * refreshes for its connected users. Clients watch the users they care about (e.g. the open chat)
 * and receive {@code presence} events when they come and go.
 */
@Slf4j
@Service
public class PresenceService implements PresenceQuery {

    private final PlatformConnectors platform;
    private final TenantConnectors connectors;
    private final ClusterService cluster;
    private final ClusterBus bus;
    private final RealtimeGateway gateway;
    private final TenantDirectory tenants;
    private final PlatformProperties.Presence settings;

    /** userKey -> local session ids */
    private final Map<String, Set<String>> localSessions = new ConcurrentHashMap<>();
    /** session id -> principal */
    private final Map<String, ChatUserPrincipal> sessionPrincipals = new ConcurrentHashMap<>();
    /** session id -> watched user keys */
    private final Map<String, Set<String>> watches = new ConcurrentHashMap<>();
    /** watched user key -> watching session ids */
    private final Map<String, Set<String>> watchers = new ConcurrentHashMap<>();

    record PresenceChange(String tenant, String userId, boolean online, Instant lastSeenAt) {
    }

    public PresenceService(PlatformConnectors platform, TenantConnectors connectors, ClusterService cluster, ClusterBus bus,
                           RealtimeGateway gateway, TenantDirectory tenants, PlatformProperties properties) {
        this.platform = platform;
        this.connectors = connectors;
        this.cluster = cluster;
        this.bus = bus;
        this.gateway = gateway;
        this.tenants = tenants;
        this.settings = properties.getPresence();
    }

    @PostConstruct
    void subscribe() {
        bus.subscribe(BusTopic.PRESENCE, null, PresenceChange.class, this::onPresenceChange);
    }

    // ------------------------------------------------------------------ session lifecycle

    public void sessionOpened(String sessionId, ChatUserPrincipal principal) {
        sessionPrincipals.put(sessionId, principal);
        boolean first;
        synchronized (localSessions) {
            Set<String> sessions = localSessions.computeIfAbsent(principal.getName(), k -> ConcurrentHashMap.newKeySet());
            first = sessions.isEmpty();
            sessions.add(sessionId);
        }
        if (first) {
            boolean wasOnline = isOnline(principal.tenant(), principal.userId());
            platform.cache().set(CacheKeys.presence(principal.tenant(), principal.userId()), cluster.getNodeId(), settings.getTtl());
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
        boolean last;
        synchronized (localSessions) {
            Set<String> sessions = localSessions.get(principal.getName());
            if (sessions != null) {
                sessions.remove(sessionId);
            }
            last = sessions == null || sessions.isEmpty();
            if (last) {
                localSessions.remove(principal.getName());
            }
        }
        if (!last) {
            return;
        }
        Instant now = Instant.now();
        try {
            connectors.store(principal.tenant()).updateLastSeen(principal.tenant(), principal.userId(), now);
        } catch (Exception e) {
            log.debug("Could not record last seen for {}: {}", principal.getName(), e.getMessage());
        }
        String presenceKey = CacheKeys.presence(principal.tenant(), principal.userId());
        // Only remove our own claim: the user may have reconnected to another node meanwhile.
        platform.cache().deleteIfEquals(presenceKey, cluster.getNodeId());
        if (platform.cache().get(presenceKey).isEmpty()) {
            broadcast(new PresenceChange(principal.tenant(), principal.userId(), false, now));
        }
    }

    /** Keep presence keys alive for everyone connected to this node. */
    @Scheduled(fixedDelayString = "#{@platformProperties.presence.heartbeat.toMillis()}",
            initialDelayString = "#{@platformProperties.presence.heartbeat.toMillis()}")
    public void heartbeat() {
        for (ChatUserPrincipal principal : connectedPrincipals()) {
            try {
                platform.cache().set(CacheKeys.presence(principal.tenant(), principal.userId()), cluster.getNodeId(),
                        settings.getTtl());
            } catch (Exception e) {
                log.debug("Presence heartbeat failed: {}", e.getMessage());
                return;
            }
        }
    }

    // ------------------------------------------------------------------ queries

    @Override
    public boolean isOnline(String tenant, String userId) {
        return platform.cache().get(CacheKeys.presence(tenant, userId)).isPresent();
    }

    @Override
    public Map<String, Boolean> onlineMany(String tenant, Collection<String> userIds) {
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(userIds));
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<String> values = platform.cache().getMany(ids.stream().map(id -> CacheKeys.presence(tenant, id)).toList());
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
        List<String> limited = userIds.stream().filter(id -> id != null && !id.isBlank()).distinct()
                .limit(settings.getMaxWatchedUsers()).toList();
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

    private List<ChatUserPrincipal> connectedPrincipals() {
        Map<String, ChatUserPrincipal> distinct = new HashMap<>();
        sessionPrincipals.values().forEach(p -> distinct.putIfAbsent(p.getName(), p));
        return List.copyOf(distinct.values());
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
            bus.publish(BusTopic.PRESENCE, change.tenant(), change);
        } catch (Exception e) {
            log.debug("Presence broadcast failed: {}", e.getMessage());
        }
    }

    private void onPresenceChange(PresenceChange change) {
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
}
