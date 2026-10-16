package com.ayush.chat.store.memory;

import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.exception.DomainException;
import com.ayush.chat.core.util.Json;
import com.ayush.chat.store.MessagePreview;
import com.ayush.chat.store.MessageStore;
import com.ayush.chat.store.UserSearch;
import com.ayush.chat.store.model.AppendResult;
import com.ayush.chat.store.model.ChatMessage;
import com.ayush.chat.store.model.ChatUser;
import com.ayush.chat.store.model.Conversation;
import com.ayush.chat.store.model.InboxItem;
import com.ayush.chat.store.model.Participant;
import com.ayush.chat.store.model.StoreStats;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reference implementation of {@link MessageStore} held entirely in memory.
 * Data is lost on restart; intended for development, tests and demos.
 * Objects are deep-copied on the way in and out so callers cannot mutate stored state.
 */
public class InMemoryMessageStore implements MessageStore {

    /** Serialises DIRECT conversation creation (check-then-insert on the direct-key index). */
    private final ReentrantLock createLock = new ReentrantLock();
    /** One lock per conversation: seq allocation and the dedupe check of an append are atomic. */
    private final Map<String, ReentrantLock> appendLocks = new ConcurrentHashMap<>();

    private final Map<String, ChatUser> users = new ConcurrentHashMap<>();
    private final Set<String> blocks = ConcurrentHashMap.newKeySet();
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
    private final Map<String, String> directIndex = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Participant>> participants = new ConcurrentHashMap<>();
    private final Map<String, NavigableMap<Long, ChatMessage>> messages = new ConcurrentHashMap<>();
    private final Map<String, String> messageIndex = new ConcurrentHashMap<>();
    private final Map<String, String> clientMsgIndex = new ConcurrentHashMap<>();

    private static String k(String... parts) {
        return String.join("\u0000", parts);
    }

    private static <T> T copy(T value, Class<T> type) {
        return value == null ? null : Json.MAPPER.convertValue(value, type);
    }

    @Override
    public String providerId() {
        return "memory";
    }

    @Override
    public HealthStatus health() {
        return HealthStatus.up("In-memory store (non-durable)",
                Map.of("users", users.size(), "conversations", conversations.size()));
    }

    @Override
    public void close() {
        // nothing to release; data is intentionally kept until garbage collected
    }

    // ------------------------------------------------------------------ users

    @Override
    public ChatUser upsertUser(ChatUser user) {
        String key = k(user.getTenantId(), user.getId());
        ChatUser stored = users.compute(key, (k, existing) -> {
            ChatUser next = copy(user, ChatUser.class);
            if (existing != null) {
                next.setCreatedAt(existing.getCreatedAt());
                if (next.getLastSeenAt() == null) {
                    next.setLastSeenAt(existing.getLastSeenAt());
                }
            }
            return next;
        });
        return copy(stored, ChatUser.class);
    }

    @Override
    public Optional<ChatUser> findUser(String tenantId, String userId) {
        return Optional.ofNullable(copy(users.get(k(tenantId, userId)), ChatUser.class));
    }

    @Override
    public List<ChatUser> findUsers(String tenantId, Collection<String> userIds) {
        List<ChatUser> result = new ArrayList<>();
        for (String id : userIds) {
            findUser(tenantId, id).ifPresent(result::add);
        }
        return result;
    }

    @Override
    public List<ChatUser> searchUsers(String tenantId, String query, int offset, int limit) {
        String q = UserSearch.normalize(query);
        return users.values().stream()
                .filter(u -> u.getTenantId().equals(tenantId))
                .filter(u -> UserSearch.matches(u, q))
                .sorted(Comparator.comparing(ChatUser::getId))
                .skip(offset).limit(limit)
                .map(u -> copy(u, ChatUser.class))
                .toList();
    }

    @Override
    public long countUsers(String tenantId) {
        return users.values().stream().filter(u -> u.getTenantId().equals(tenantId)).count();
    }

    @Override
    public void updateLastSeen(String tenantId, String userId, Instant lastSeenAt) {
        users.computeIfPresent(k(tenantId, userId), (k, u) -> {
            u.setLastSeenAt(lastSeenAt);
            return u;
        });
    }

    // ------------------------------------------------------------------ blocks

    @Override
    public void setBlocked(String tenantId, String blockerId, String blockedId, boolean blocked) {
        String key = k(tenantId, blockerId, blockedId);
        if (blocked) {
            blocks.add(key);
        } else {
            blocks.remove(key);
        }
    }

    @Override
    public boolean isBlocked(String tenantId, String blockerId, String blockedId) {
        return blocks.contains(k(tenantId, blockerId, blockedId));
    }

    @Override
    public List<String> listBlocked(String tenantId, String blockerId) {
        String prefix = k(tenantId, blockerId) + "\u0000";
        return blocks.stream().filter(b -> b.startsWith(prefix)).map(b -> b.substring(prefix.length())).sorted().toList();
    }

    // ------------------------------------------------------------------ conversations

    @Override
    public Conversation createConversation(Conversation conversation, List<Participant> initial) {
        createLock.lock();
        try {
            if (conversation.getType() == Conversation.Type.DIRECT) {
                String existingId = directIndex.get(k(conversation.getTenantId(), conversation.getDirectKey()));
                if (existingId != null) {
                    return copy(conversations.get(k(conversation.getTenantId(), existingId)), Conversation.class);
                }
                directIndex.put(k(conversation.getTenantId(), conversation.getDirectKey()), conversation.getId());
            }
            String key = k(conversation.getTenantId(), conversation.getId());
            conversations.put(key, copy(conversation, Conversation.class));
            Map<String, Participant> members = new ConcurrentHashMap<>();
            for (Participant p : initial) {
                members.put(p.getUserId(), copy(p, Participant.class));
            }
            participants.put(key, members);
            messages.put(key, new ConcurrentSkipListMap<>());
            return copy(conversation, Conversation.class);
        } finally {
            createLock.unlock();
        }
    }

    @Override
    public Optional<Conversation> findConversation(String tenantId, String conversationId) {
        return Optional.ofNullable(copy(conversations.get(k(tenantId, conversationId)), Conversation.class));
    }

    @Override
    public Optional<Conversation> findDirectConversation(String tenantId, String directKey) {
        String id = directIndex.get(k(tenantId, directKey));
        return id == null ? Optional.empty() : findConversation(tenantId, id);
    }

    @Override
    public void updateConversation(Conversation conversation) {
        conversations.computeIfPresent(k(conversation.getTenantId(), conversation.getId()), (k, c) -> {
            c.setTitle(conversation.getTitle());
            c.setDescription(conversation.getDescription());
            c.setAvatarUrl(conversation.getAvatarUrl());
            c.setMetadata(conversation.getMetadata());
            c.setUpdatedAt(conversation.getUpdatedAt());
            return c;
        });
    }

    @Override
    public List<InboxItem> listInbox(String tenantId, String userId, int limit, Instant before) {
        List<InboxItem> items = new ArrayList<>();
        participants.forEach((convKey, members) -> {
            Participant self = members.get(userId);
            if (self == null || !self.isActive() || !Objects.equals(self.getTenantId(), tenantId)) {
                return;
            }
            Conversation c = conversations.get(convKey);
            if (c != null && (before == null || c.activityAt().isBefore(before))) {
                items.add(new InboxItem(copy(c, Conversation.class), copy(self, Participant.class)));
            }
        });
        items.sort(Comparator.comparing((InboxItem i) -> i.conversation().activityAt()).reversed()
                .thenComparing(i -> i.conversation().getId()));
        return items.size() > limit ? new ArrayList<>(items.subList(0, limit)) : items;
    }

    // ------------------------------------------------------------------ participants

    @Override
    public List<Participant> listParticipants(String tenantId, String conversationId) {
        return participants.getOrDefault(k(tenantId, conversationId), Map.of()).values().stream()
                .filter(Participant::isActive)
                .sorted(Comparator.comparing(Participant::getUserId))
                .map(p -> copy(p, Participant.class))
                .toList();
    }

    @Override
    public Optional<Participant> findParticipant(String tenantId, String conversationId, String userId) {
        return Optional.ofNullable(copy(participants.getOrDefault(k(tenantId, conversationId), Map.of()).get(userId),
                Participant.class));
    }

    @Override
    public void upsertParticipant(Participant participant) {
        participants.computeIfAbsent(k(participant.getTenantId(), participant.getConversationId()),
                        x -> new ConcurrentHashMap<>())
                .merge(participant.getUserId(), copy(participant, Participant.class), (existing, next) -> {
                    next.setDeliveredSeq(Math.max(existing.getDeliveredSeq(), next.getDeliveredSeq()));
                    next.setReadSeq(Math.max(existing.getReadSeq(), next.getReadSeq()));
                    return next;
                });
    }

    @Override
    public void removeParticipant(String tenantId, String conversationId, String userId, Instant at) {
        participants.getOrDefault(k(tenantId, conversationId), Map.of()).computeIfPresent(userId, (u, p) -> {
            p.setLeftAt(at);
            p.setUpdatedAt(at);
            return p;
        });
    }

    @Override
    public void advanceReadState(String tenantId, String conversationId, String userId, Long deliveredSeq, Long readSeq) {
        participants.getOrDefault(k(tenantId, conversationId), Map.of()).computeIfPresent(userId, (u, p) -> {
            if (deliveredSeq != null) {
                p.setDeliveredSeq(Math.max(p.getDeliveredSeq(), deliveredSeq));
            }
            if (readSeq != null) {
                p.setReadSeq(Math.max(p.getReadSeq(), readSeq));
                p.setDeliveredSeq(Math.max(p.getDeliveredSeq(), readSeq));
            }
            p.setUpdatedAt(Instant.now());
            return p;
        });
    }

    // ------------------------------------------------------------------ messages

    @Override
    public AppendResult appendMessage(ChatMessage message) {
        String convKey = k(message.getTenantId(), message.getConversationId());
        Conversation conv = conversations.get(convKey);
        if (conv == null) {
            throw DomainException.notFound("Conversation");
        }
        ReentrantLock lock = appendLocks.computeIfAbsent(convKey, key -> new ReentrantLock());
        lock.lock();
        try {
            if (message.getClientMsgId() != null) {
                String dedupeKey = k(convKey, message.getSenderId(), message.getClientMsgId());
                String existingId = clientMsgIndex.get(dedupeKey);
                if (existingId != null) {
                    return new AppendResult(findMessage(message.getTenantId(), message.getConversationId(), existingId)
                            .orElseThrow(), true);
                }
                clientMsgIndex.put(dedupeKey, message.getId());
            }
            long seq = conv.getLastSeq() + 1;
            ChatMessage stored = copy(message, ChatMessage.class);
            stored.setSeq(seq);
            messages.get(convKey).put(seq, stored);
            messageIndex.put(k(convKey, stored.getId()), String.valueOf(seq));
            conv.setLastSeq(seq);
            conv.setLastMessageId(stored.getId());
            conv.setLastMessagePreview(MessagePreview.of(stored));
            conv.setLastMessageSenderId(stored.getSenderId());
            conv.setLastMessageAt(stored.getCreatedAt());
            conv.setUpdatedAt(stored.getCreatedAt());
            advanceReadState(message.getTenantId(), message.getConversationId(), message.getSenderId(), seq, seq);
            return new AppendResult(copy(stored, ChatMessage.class), false);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<ChatMessage> findMessage(String tenantId, String conversationId, String messageId) {
        String convKey = k(tenantId, conversationId);
        String seq = messageIndex.get(k(convKey, messageId));
        if (seq == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(copy(messages.get(convKey).get(Long.parseLong(seq)), ChatMessage.class));
    }

    @Override
    public List<ChatMessage> listMessages(String tenantId, String conversationId, Long afterSeq, Long beforeSeq, int limit) {
        NavigableMap<Long, ChatMessage> all = messages.get(k(tenantId, conversationId));
        if (all == null) {
            return List.of();
        }
        List<ChatMessage> result = new ArrayList<>();
        if (afterSeq != null) {
            for (ChatMessage m : all.tailMap(afterSeq, false).values()) {
                if (result.size() >= limit) break;
                result.add(copy(m, ChatMessage.class));
            }
            return result;
        }
        NavigableMap<Long, ChatMessage> head = beforeSeq == null ? all : all.headMap(beforeSeq, false);
        for (ChatMessage m : head.descendingMap().values()) {
            if (result.size() >= limit) break;
            result.add(copy(m, ChatMessage.class));
        }
        Collections.reverse(result);
        return result;
    }

    @Override
    public void updateMessage(ChatMessage message) {
        String convKey = k(message.getTenantId(), message.getConversationId());
        NavigableMap<Long, ChatMessage> all = messages.get(convKey);
        if (all == null) {
            return;
        }
        all.computeIfPresent(message.getSeq(), (s, m) -> {
            if (!m.getId().equals(message.getId())) {
                return m;
            }
            m.setContent(message.getContent());
            m.setAttachment(copy(message.getAttachment(), ChatMessage.Attachment.class));
            m.setReactions(copy(message, ChatMessage.class).getReactions());
            m.setEdited(message.isEdited());
            m.setEditedAt(message.getEditedAt());
            m.setDeleted(message.isDeleted());
            m.setDeletedAt(message.getDeletedAt());
            m.setMetadata(message.getMetadata());
            return m;
        });
    }

    @Override
    public StoreStats stats(String tenantId) {
        long u = users.values().stream().filter(x -> tenantId == null || x.getTenantId().equals(tenantId)).count();
        long c = conversations.values().stream().filter(x -> tenantId == null || x.getTenantId().equals(tenantId)).count();
        long m = conversations.entrySet().stream()
                .filter(e -> tenantId == null || e.getValue().getTenantId().equals(tenantId))
                .mapToLong(e -> messages.getOrDefault(e.getKey(), new ConcurrentSkipListMap<>()).size())
                .sum();
        return new StoreStats(u, c, m);
    }
}
