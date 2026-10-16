package com.ayush.chat.chat.service;

import com.ayush.chat.chat.dto.response.ReceiptView;
import com.ayush.chat.chat.dto.response.TypingView;
import com.ayush.chat.chat.event.ChatEvent;
import com.ayush.chat.core.exception.DomainException;
import com.ayush.chat.store.model.Conversation;
import com.ayush.chat.store.model.Participant;
import com.ayush.chat.tenant.model.Feature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Delivery / read watermarks and typing indicators. Watermarks only move forward and are capped at
 * the conversation's last seq; without the read-receipts feature only the user's own devices hear
 * about them.
 */
@Service
@RequiredArgsConstructor
public class ReceiptService {

    private final TenantScopes scopes;
    private final ConversationAccess access;
    private final ConversationNotifier notifier;

    public void markRead(String tenant, String me, String conversationId, long seq) {
        advance(tenant, me, conversationId, null, seq);
    }

    public void markDelivered(String tenant, String me, String conversationId, long seq) {
        advance(tenant, me, conversationId, seq, null);
    }

    /** Ephemeral: relayed to the other members, never stored. Silently ignored when the feature is off. */
    public void typing(String tenant, String me, String conversationId, boolean typing) {
        TenantScope scope = scopes.of(tenant);
        if (!scope.tenant().has(Feature.TYPING_INDICATORS)) {
            return;
        }
        List<Participant> members = access.membersCached(scope, conversationId);
        if (members.stream().noneMatch(p -> p.getUserId().equals(me))) {
            throw DomainException.notFound("Conversation");
        }
        List<String> others = members.stream().map(Participant::getUserId).filter(id -> !id.equals(me)).toList();
        notifier.toMembers(scope, others, ChatEvent.of(ChatEvent.TYPING, conversationId, new TypingView(me, typing)));
    }

    private void advance(String tenant, String me, String conversationId, Long delivered, Long read) {
        TenantScope scope = scopes.of(tenant);
        Conversation conversation = access.require(scope, conversationId);
        Participant self = access.requireMember(scope, conversationId, me);
        long cap = conversation.getLastSeq();
        Long d = delivered == null ? null : Math.min(delivered, cap);
        Long r = read == null ? null : Math.min(read, cap);
        if ((d == null || d <= self.getDeliveredSeq()) && (r == null || r <= self.getReadSeq())) {
            return;
        }
        scope.store().advanceReadState(scope.slug(), conversationId, me, d, r);
        Participant updated = scope.store().findParticipant(scope.slug(), conversationId, me).orElse(self);
        ChatEvent event = ChatEvent.of(ChatEvent.RECEIPT, conversationId,
                new ReceiptView(me, updated.getDeliveredSeq(), updated.getReadSeq()));
        List<String> audience = scope.tenant().has(Feature.READ_RECEIPTS)
                ? access.membersCached(scope, conversationId).stream().map(Participant::getUserId).toList()
                : List.of(me);
        notifier.toMembers(scope, audience, event);
    }
}
