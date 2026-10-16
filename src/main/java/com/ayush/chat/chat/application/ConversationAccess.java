package com.ayush.chat.chat.application;

import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.store.model.Conversation;
import com.ayush.chat.store.model.Participant;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Loads conversations and checks membership. Hot paths (send, typing, receipts) use short-lived
 * node-local caches; every membership change invalidates them cluster-wide via the control channel.
 * Non-members get 404, never 403, so conversation ids cannot be probed.
 */
@Component
public class ConversationAccess {

    private final Cache<String, Conversation> conversations;
    private final Cache<String, List<Participant>> members;

    public ConversationAccess(PlatformProperties properties) {
        PlatformProperties.Caches caches = properties.getCaches();
        this.conversations = Caffeine.newBuilder().expireAfterWrite(caches.getConversation()).maximumSize(200_000).build();
        this.members = Caffeine.newBuilder().expireAfterWrite(caches.getMembership()).maximumSize(100_000).build();
    }

    public Conversation require(TenantScope scope, String conversationId) {
        return scope.store().findConversation(scope.slug(), conversationId)
                .orElseThrow(() -> DomainException.notFound("Conversation"));
    }

    /** Type and direct key never change and title is display-only, so a cached copy is safe for checks. */
    public Conversation cached(TenantScope scope, String conversationId) {
        Conversation hit = conversations.getIfPresent(key(scope.slug(), conversationId));
        if (hit != null) {
            return hit;
        }
        Conversation loaded = require(scope, conversationId);
        conversations.put(key(scope.slug(), conversationId), loaded);
        return loaded;
    }

    public Participant requireMember(TenantScope scope, String conversationId, String userId) {
        return scope.store().findParticipant(scope.slug(), conversationId, userId).filter(Participant::isActive)
                .orElseThrow(() -> DomainException.notFound("Conversation"));
    }

    public List<Participant> membersCached(TenantScope scope, String conversationId) {
        return members.get(key(scope.slug(), conversationId), k -> scope.store().listParticipants(scope.slug(), conversationId));
    }

    public void forgetMembers(String tenant, String conversationId) {
        members.invalidate(key(tenant, conversationId));
    }

    /** Drop this node's cached state for a conversation. */
    public void forget(String tenant, String conversationId) {
        members.invalidate(key(tenant, conversationId));
        conversations.invalidate(key(tenant, conversationId));
    }

    static void requireGroup(Conversation conversation) {
        if (conversation.getType() != Conversation.Type.GROUP) {
            throw DomainException.badRequest("This operation is only available for groups");
        }
    }

    static void requireAdmin(Participant participant) {
        if (!participant.isAdmin()) {
            throw DomainException.forbidden("Only group admins can do this");
        }
    }

    /** Lock serialising membership and per-member settings changes of one conversation. */
    static String membersLock(String tenant, String conversationId) {
        return "members:" + tenant + ":" + conversationId;
    }

    private static String key(String tenant, String id) {
        return tenant + "::" + id;
    }
}
