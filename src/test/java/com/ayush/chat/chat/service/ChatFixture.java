package com.ayush.chat.chat.service;

import com.ayush.chat.audit.service.AuditService;
import com.ayush.chat.chat.dto.request.UpsertUserRequest;
import com.ayush.chat.chat.event.ChatEvent;
import com.ayush.chat.chat.event.ControlMessage;
import com.ayush.chat.chat.event.DomainEvent;
import com.ayush.chat.chat.mapper.ChatViewMapper;
import com.ayush.chat.chat.port.PresenceQuery;
import com.ayush.chat.chat.port.RealtimePublisher;
import com.ayush.chat.core.cluster.DistributedLock;
import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.connector.runtime.TenantConnectors;
import com.ayush.chat.store.memory.InMemoryMessageStore;
import com.ayush.chat.tenant.entity.Tenant;
import com.ayush.chat.tenant.model.Feature;
import com.ayush.chat.tenant.model.TenantConfig;
import com.ayush.chat.tenant.model.TenantLimits;
import com.ayush.chat.tenant.model.TenantSnapshot;
import com.ayush.chat.tenant.service.QuotaService;
import com.ayush.chat.tenant.service.TenantDirectory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The messaging application services wired together over an in-memory store, with recording fakes
 * for the outbound ports. Infrastructure the domain does not own (tenant directory, quotas, locks)
 * is stubbed, so each test exercises exactly the domain rules.
 */
final class ChatFixture {

    static final String TENANT = "acme";

    final InMemoryMessageStore store = new InMemoryMessageStore();
    final RecordingPublisher published = new RecordingPublisher();
    final FakePresence presence = new FakePresence();
    final QuotaService quotas = mock(QuotaService.class);
    final AuditService audit = mock(AuditService.class);
    private TenantSnapshot snapshot = snapshot(EnumSet.allOf(Feature.class));

    final UserDirectoryService users;
    final BlockService blocks;
    final ConversationService conversations;
    final MembershipService membership;
    final MessageService messages;
    final ReceiptService receipts;
    final UserStatusService status;

    @SuppressWarnings("unchecked")
    ChatFixture() {
        PlatformProperties properties = new PlatformProperties();
        TenantDirectory directory = mock(TenantDirectory.class);
        when(directory.requireUsable(TENANT)).thenAnswer(inv -> snapshot);
        TenantConnectors connectors = mock(TenantConnectors.class);
        when(connectors.store(TENANT)).thenReturn(store);
        DistributedLock locks = mock(DistributedLock.class);
        when(locks.withLock(anyString(), any(Supplier.class))).thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(1)).get());
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(locks).withLock(anyString(), any(Runnable.class));

        TenantScopes scopes = new TenantScopes(directory, connectors);
        ConversationAccess access = new ConversationAccess(properties);
        ChatViewMapper views = new ChatViewMapper(presence);
        ConversationNotifier notifier = new ConversationNotifier(published, views, access);
        status = new UserStatusService(connectors, properties);
        blocks = new BlockService(scopes, notifier, properties);
        users = new UserDirectoryService(scopes, views, status, notifier, audit);
        messages = new MessageService(scopes, access, blocks, status, quotas, mock(MetricsService.class), locks, notifier);
        conversations = new ConversationService(scopes, access, views, notifier, messages, locks);
        membership = new MembershipService(scopes, access, views, notifier, messages, conversations, locks);
        receipts = new ReceiptService(scopes, access, notifier);
    }

    void features(Set<Feature> enabled) {
        snapshot = snapshot(enabled);
    }

    void limits(TenantLimits limits) {
        snapshot = new TenantSnapshot(1L, TENANT, "Acme", Tenant.Status.ACTIVE, "pro", "Pro", limits,
                TenantConfig.Settings.DEFAULT, TenantConfig.Branding.DEFAULT, TenantConfig.Auth.DEFAULT,
                snapshot.features(), null);
    }

    void user(String id) {
        users.upsert(TENANT, new UpsertUserRequest(id, id.toUpperCase(), null, null, null, null, Map.of()));
    }

    static TenantSnapshot snapshot(Set<Feature> features) {
        return new TenantSnapshot(1L, TENANT, "Acme", Tenant.Status.ACTIVE, "pro", "Pro", TenantLimits.NONE,
                TenantConfig.Settings.DEFAULT, TenantConfig.Branding.DEFAULT, TenantConfig.Auth.DEFAULT,
                features.isEmpty() ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(features), null);
    }

    record Delivery(String tenant, List<String> users, ChatEvent event) {
    }

    static final class RecordingPublisher implements RealtimePublisher {
        final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
        final List<DomainEvent> domainEvents = new CopyOnWriteArrayList<>();
        final List<ControlMessage> control = new CopyOnWriteArrayList<>();

        @Override
        public void toUsers(String tenant, Collection<String> userIds, ChatEvent event) {
            deliveries.add(new Delivery(tenant, List.copyOf(userIds), event));
        }

        @Override
        public void publishDomainEvent(DomainEvent event) {
            domainEvents.add(event);
        }

        @Override
        public void publishControl(ControlMessage message) {
            control.add(message);
        }

        List<Delivery> ofType(String type) {
            return deliveries.stream().filter(d -> d.event().type().equals(type)).toList();
        }

        void clear() {
            deliveries.clear();
            domainEvents.clear();
            control.clear();
        }
    }

    static final class FakePresence implements PresenceQuery {
        final List<String> online = new ArrayList<>();

        @Override
        public boolean isOnline(String tenant, String userId) {
            return online.contains(userId);
        }

        @Override
        public Map<String, Boolean> onlineMany(String tenant, Collection<String> userIds) {
            Map<String, Boolean> out = new HashMap<>();
            userIds.forEach(id -> out.put(id, online.contains(id)));
            return out;
        }
    }
}
