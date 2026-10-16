package com.ayush.chat.chat.api;

import com.ayush.chat.auth.Principals.ChatUserPrincipal;
import com.ayush.chat.chat.ChatService;
import com.ayush.chat.chat.Views.ConversationView;
import com.ayush.chat.chat.Views.MessageView;
import com.ayush.chat.core.error.ApiException;
import com.ayush.chat.store.model.ChatMessage;
import com.ayush.chat.store.model.Participant;
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
import java.util.Map;

/**
 * Conversations, membership, messages, receipts and typing for the signed-in user.
 */
@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ChatService chat;

    public record DirectRequest(String userId) {
    }

    public record GroupRequest(String title, List<String> members, String description, String avatarUrl) {
    }

    public record GroupUpdate(String title, String description, String avatarUrl) {
    }

    public record SettingsUpdate(Boolean muted, Boolean archived, Boolean pinned) {
    }

    public record MembersRequest(List<String> userIds) {
    }

    public record RoleUpdate(Participant.Role role) {
    }

    public record MessageRequest(String clientMsgId, ChatMessage.Type type, String content, ChatMessage.Attachment attachment,
                                 String replyToId, Boolean forwarded, Map<String, Object> metadata) {
    }

    public record EditRequest(String content) {
    }

    public record ReactionRequest(String emoji) {
    }

    public record SeqRequest(Long seq) {
    }

    public record TypingRequest(boolean typing) {
    }

    @GetMapping
    public List<ConversationView> inbox(@AuthenticationPrincipal ChatUserPrincipal me,
                                        @RequestParam(defaultValue = "50") int limit,
                                        @RequestParam(required = false) Instant before) {
        return chat.inbox(me.tenant(), me.userId(), limit, before);
    }

    @PostMapping("/direct")
    public ConversationView direct(@AuthenticationPrincipal ChatUserPrincipal me, @RequestBody DirectRequest request) {
        return chat.openDirect(me.tenant(), me.userId(), request.userId());
    }

    @PostMapping("/group")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationView group(@AuthenticationPrincipal ChatUserPrincipal me, @RequestBody GroupRequest request) {
        return chat.createGroup(me.tenant(), me.userId(), request.title(), request.members(), request.description(),
                request.avatarUrl());
    }

    @GetMapping("/{id}")
    public ConversationView get(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id) {
        return chat.getConversation(me.tenant(), me.userId(), id);
    }

    @PatchMapping("/{id}")
    public ConversationView update(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                                   @RequestBody GroupUpdate update) {
        return chat.updateGroup(me.tenant(), me.userId(), id, update.title(), update.description(), update.avatarUrl());
    }

    @PatchMapping("/{id}/settings")
    public ConversationView settings(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                                     @RequestBody SettingsUpdate update) {
        return chat.updateSettings(me.tenant(), me.userId(), id, update.muted(), update.archived(), update.pinned());
    }

    @PostMapping("/{id}/members")
    public ConversationView addMembers(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                                       @RequestBody MembersRequest request) {
        return chat.addMembers(me.tenant(), me.userId(), id, request.userIds());
    }

    @PatchMapping("/{id}/members/{userId}")
    public ConversationView setRole(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                                    @PathVariable String userId, @RequestBody RoleUpdate update) {
        return chat.setRole(me.tenant(), me.userId(), id, userId, update.role());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                             @PathVariable String userId) {
        chat.removeMember(me.tenant(), me.userId(), id, userId);
    }

    @GetMapping("/{id}/messages")
    public List<MessageView> messages(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                                      @RequestParam(required = false) Long after,
                                      @RequestParam(required = false) Long before,
                                      @RequestParam(defaultValue = "50") int limit) {
        return chat.messages(me.tenant(), me.userId(), id, after, before, limit);
    }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageView send(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                            @RequestBody MessageRequest request) {
        return chat.send(me.tenant(), me.userId(), id, new ChatService.SendInput(request.clientMsgId(), request.type(),
                request.content(), request.attachment(), request.replyToId(), request.forwarded(), request.metadata())).message();
    }

    @PatchMapping("/{id}/messages/{messageId}")
    public MessageView edit(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                            @PathVariable String messageId, @RequestBody EditRequest request) {
        return chat.edit(me.tenant(), me.userId(), id, messageId, request.content());
    }

    @DeleteMapping("/{id}/messages/{messageId}")
    public MessageView delete(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                              @PathVariable String messageId) {
        return chat.delete(me.tenant(), me.userId(), id, messageId);
    }

    @PutMapping("/{id}/messages/{messageId}/reactions")
    public MessageView react(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                             @PathVariable String messageId, @RequestBody ReactionRequest request) {
        if (request.emoji() == null) {
            throw ApiException.badRequest("emoji is required");
        }
        return chat.react(me.tenant(), me.userId(), id, messageId, request.emoji());
    }

    @DeleteMapping("/{id}/messages/{messageId}/reactions")
    public MessageView unreact(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                               @PathVariable String messageId) {
        return chat.react(me.tenant(), me.userId(), id, messageId, null);
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id, @RequestBody SeqRequest request) {
        chat.markRead(me.tenant(), me.userId(), id, requireSeq(request));
    }

    @PostMapping("/{id}/delivered")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delivered(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                          @RequestBody SeqRequest request) {
        chat.markDelivered(me.tenant(), me.userId(), id, requireSeq(request));
    }

    @PostMapping("/{id}/typing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void typing(@AuthenticationPrincipal ChatUserPrincipal me, @PathVariable String id,
                       @RequestBody TypingRequest request) {
        chat.typing(me.tenant(), me.userId(), id, request.typing());
    }

    private static long requireSeq(SeqRequest request) {
        if (request == null || request.seq() == null || request.seq() < 0) {
            throw ApiException.badRequest("seq is required");
        }
        return request.seq();
    }
}
