package com.ayush.chat.chat.controller;

import com.ayush.chat.auth.security.Principals.ChatUserPrincipal;
import com.ayush.chat.chat.dto.command.SendMessageCommand;
import com.ayush.chat.chat.dto.request.AddMembersRequest;
import com.ayush.chat.chat.dto.request.ConversationSettingsRequest;
import com.ayush.chat.chat.dto.request.CreateGroupRequest;
import com.ayush.chat.chat.dto.request.DirectConversationRequest;
import com.ayush.chat.chat.dto.request.EditMessageRequest;
import com.ayush.chat.chat.dto.request.MemberRoleRequest;
import com.ayush.chat.chat.dto.request.ReactionRequest;
import com.ayush.chat.chat.dto.request.ReceiptRequest;
import com.ayush.chat.chat.dto.request.SendMessageRequest;
import com.ayush.chat.chat.dto.request.TypingRequest;
import com.ayush.chat.chat.dto.request.UpdateGroupRequest;
import com.ayush.chat.chat.dto.response.ConversationView;
import com.ayush.chat.chat.dto.response.MessageView;
import com.ayush.chat.chat.service.ConversationService;
import com.ayush.chat.chat.service.MembershipService;
import com.ayush.chat.chat.service.MessageService;
import com.ayush.chat.chat.service.ReceiptService;
import com.ayush.chat.core.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Conversations, membership, messages, receipts and typing for the signed-in user.
 */
@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversations;
    private final MembershipService membership;
    private final MessageService messages;
    private final ReceiptService receipts;

    @GetMapping
    public List<ConversationView> inbox(@AuthenticationPrincipal ChatUserPrincipal me,
                                        @RequestParam(defaultValue = "50") int limit,
                                        @RequestParam(required = false) Instant before) {
        return conversations.inbox(me.tenant(), me.userId(), limit, before);
    }

    @PostMapping("/direct")
    public ConversationView direct(@AuthenticationPrincipal ChatUserPrincipal me, @RequestBody DirectConversationRequest request) {
        return conversations.openDirect(me.tenant(), me.userId(), request.userId());
    }

    @PostMapping("/group")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationView group(@AuthenticationPrincipal ChatUserPrincipal me, @RequestBody CreateGroupRequest request) {
        return conversations.createGroup(me.tenant(), me.userId(), request.title(), request.members(), request.description(),
                request.avatarUrl());
    }

    @GetMapping("/{id}")
    public ConversationView get(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id) {
        return conversations.get(me.tenant(), me.userId(), id);
    }

    @PatchMapping("/{id}")
    public ConversationView update(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                                   @RequestBody UpdateGroupRequest update) {
        return conversations.updateGroup(me.tenant(), me.userId(), id, update.title(), update.description(), update.avatarUrl());
    }

    @PatchMapping("/{id}/settings")
    public ConversationView settings(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                                     @RequestBody ConversationSettingsRequest update) {
        return conversations.updateSettings(me.tenant(), me.userId(), id, update.muted(), update.archived(), update.pinned());
    }

    @PostMapping("/{id}/members")
    public ConversationView addMembers(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                                       @RequestBody AddMembersRequest request) {
        return membership.addMembers(me.tenant(), me.userId(), id, request.userIds());
    }

    @PatchMapping("/{id}/members/{userId}")
    public ConversationView setRole(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                                    @PathVariable String userId, @RequestBody MemberRoleRequest update) {
        return membership.setRole(me.tenant(), me.userId(), id, userId, update.role());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                             @PathVariable String userId) {
        membership.removeMember(me.tenant(), me.userId(), id, userId);
    }

    @GetMapping("/{id}/messages")
    public List<MessageView> messages(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                                      @RequestParam(required = false) Long after,
                                      @RequestParam(required = false) Long before,
                                      @RequestParam(defaultValue = "50") int limit) {
        return messages.list(me.tenant(), me.userId(), id, after, before, limit);
    }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageView send(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                            @RequestBody SendMessageRequest request) {
        return messages.send(me.tenant(), me.userId(), id, new SendMessageCommand(request.clientMsgId(), request.type(),
                request.content(), request.attachment(), request.replyToId(), request.forwarded(), request.metadata())).message();
    }

    @PatchMapping("/{id}/messages/{messageId}")
    public MessageView edit(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                            @PathVariable String messageId, @RequestBody EditMessageRequest request) {
        return messages.edit(me.tenant(), me.userId(), id, messageId, request.content());
    }

    @DeleteMapping("/{id}/messages/{messageId}")
    public MessageView delete(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                              @PathVariable String messageId) {
        return messages.delete(me.tenant(), me.userId(), id, messageId);
    }

    @PutMapping("/{id}/messages/{messageId}/reactions")
    public MessageView react(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                             @PathVariable String messageId, @RequestBody ReactionRequest request) {
        if (request.emoji() == null) {
            throw DomainException.badRequest("emoji is required");
        }
        return messages.react(me.tenant(), me.userId(), id, messageId, request.emoji());
    }

    @DeleteMapping("/{id}/messages/{messageId}/reactions")
    public MessageView unreact(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                               @PathVariable String messageId) {
        return messages.react(me.tenant(), me.userId(), id, messageId, null);
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id, @RequestBody ReceiptRequest request) {
        receipts.markRead(me.tenant(), me.userId(), id, requireSeq(request));
    }

    @PostMapping("/{id}/delivered")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delivered(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                          @RequestBody ReceiptRequest request) {
        receipts.markDelivered(me.tenant(), me.userId(), id, requireSeq(request));
    }

    @PostMapping("/{id}/typing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void typing(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                       @RequestBody TypingRequest request) {
        receipts.typing(me.tenant(), me.userId(), id, request.typing());
    }

    private static long requireSeq(ReceiptRequest request) {
        if (request == null || request.seq() == null || request.seq() < 0) {
            throw DomainException.badRequest("seq is required");
        }
        return request.seq();
    }
}
