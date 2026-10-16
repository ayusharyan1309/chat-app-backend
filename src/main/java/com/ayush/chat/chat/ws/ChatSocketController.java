package com.ayush.chat.chat.ws;

import com.ayush.chat.auth.Principals.ChatUserPrincipal;
import com.ayush.chat.chat.ChatService;
import com.ayush.chat.chat.PresenceService;
import com.ayush.chat.chat.RealtimeGateway;
import com.ayush.chat.chat.event.ChatEvent;
import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.store.model.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * STOMP application destinations ({@code /app/...}). Failures are reported to the caller as an
 * {@code error} event rather than tearing down the connection.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatSocketController {

    private final ChatService chat;
    private final PresenceService presence;
    private final RealtimeGateway gateway;

    public record SendFrame(String conversationId, String clientMsgId, ChatMessage.Type type, String content,
                            ChatMessage.Attachment attachment, String replyToId, Boolean forwarded,
                            Map<String, Object> metadata) {
    }

    public record TypingFrame(String conversationId, boolean typing) {
    }

    public record ReceiptFrame(String conversationId, long seq, String kind) {
    }

    public record WatchFrame(List<String> userIds) {
    }

    @MessageMapping("/messages.send")
    public void send(@Payload SendFrame frame, Principal principal) {
        ChatUserPrincipal me = (ChatUserPrincipal) principal;
        try {
            ChatService.SendResult result = chat.send(me.tenant(), me.userId(), frame.conversationId(),
                    new ChatService.SendInput(frame.clientMsgId(), frame.type(), frame.content(), frame.attachment(),
                            frame.replyToId(), frame.forwarded(), frame.metadata()));
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("clientMsgId", result.message().clientMsgId());
            ack.put("message", result.message());
            ack.put("duplicate", result.duplicate());
            gateway.deliverLocally(me.tenant(), me.userId(), ChatEvent.of(ChatEvent.MESSAGE_ACK, frame.conversationId(), ack));
        } catch (Exception e) {
            error(me, frame.conversationId(), e, frame.clientMsgId());
        }
    }

    @MessageMapping("/typing")
    public void typing(@Payload TypingFrame frame, Principal principal) {
        ChatUserPrincipal me = (ChatUserPrincipal) principal;
        try {
            chat.typing(me.tenant(), me.userId(), frame.conversationId(), frame.typing());
        } catch (Exception e) {
            error(me, frame.conversationId(), e, null);
        }
    }

    @MessageMapping("/receipts")
    public void receipt(@Payload ReceiptFrame frame, Principal principal) {
        ChatUserPrincipal me = (ChatUserPrincipal) principal;
        try {
            if ("read".equalsIgnoreCase(frame.kind())) {
                chat.markRead(me.tenant(), me.userId(), frame.conversationId(), frame.seq());
            } else {
                chat.markDelivered(me.tenant(), me.userId(), frame.conversationId(), frame.seq());
            }
        } catch (Exception e) {
            error(me, frame.conversationId(), e, null);
        }
    }

    @MessageMapping("/presence.watch")
    public void watch(@Payload WatchFrame frame, Principal principal, @Header("simpSessionId") String sessionId) {
        ChatUserPrincipal me = (ChatUserPrincipal) principal;
        try {
            presence.watch(sessionId, me, frame.userIds() == null ? List.of() : frame.userIds());
        } catch (Exception e) {
            error(me, null, e, null);
        }
    }

    private void error(ChatUserPrincipal me, String conversationId, Exception e, String clientMsgId) {
        String code = e instanceof DomainException api ? api.getCode() : "internal_error";
        String message = e instanceof DomainException ? e.getMessage() : "Unexpected server error";
        if (!(e instanceof DomainException)) {
            log.error("Socket operation failed for {}", me.getName(), e);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("message", message);
        if (clientMsgId != null) {
            data.put("clientMsgId", clientMsgId);
        }
        gateway.deliverLocally(me.tenant(), me.userId(), ChatEvent.of(ChatEvent.ERROR, conversationId, data));
    }
}
