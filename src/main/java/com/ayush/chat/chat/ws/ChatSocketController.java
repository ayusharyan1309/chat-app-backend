package com.ayush.chat.chat.ws;

import com.ayush.chat.auth.Principals.ChatUserPrincipal;
import com.ayush.chat.chat.Views.MessageView;
import com.ayush.chat.chat.application.MessageService;
import com.ayush.chat.chat.application.ReceiptService;
import com.ayush.chat.chat.event.ChatEvent;
import com.ayush.chat.chat.realtime.PresenceService;
import com.ayush.chat.chat.realtime.RealtimeGateway;
import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.core.error.ErrorCode;
import com.ayush.chat.store.model.ChatMessage;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;
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

    private final MessageService messages;
    private final ReceiptService receipts;
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

    /** Sent to the sender's own sessions once a message is durable. */
    public record MessageAck(String clientMsgId, MessageView message, boolean duplicate) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorPayload(String code, String message, String clientMsgId) {
    }

    @MessageMapping("/messages.send")
    public void send(@Payload SendFrame frame, Principal principal) {
        ChatUserPrincipal me = (ChatUserPrincipal) principal;
        handle(me, frame.conversationId(), frame.clientMsgId(), () -> {
            MessageService.SendResult result = messages.send(me.tenant(), me.userId(), frame.conversationId(),
                    new MessageService.SendInput(frame.clientMsgId(), frame.type(), frame.content(), frame.attachment(),
                            frame.replyToId(), frame.forwarded(), frame.metadata()));
            gateway.deliverLocally(me.tenant(), me.userId(), ChatEvent.of(ChatEvent.MESSAGE_ACK, frame.conversationId(),
                    new MessageAck(result.message().clientMsgId(), result.message(), result.duplicate())));
        });
    }

    @MessageMapping("/typing")
    public void typing(@Payload TypingFrame frame, Principal principal) {
        ChatUserPrincipal me = (ChatUserPrincipal) principal;
        handle(me, frame.conversationId(), null,
                () -> receipts.typing(me.tenant(), me.userId(), frame.conversationId(), frame.typing()));
    }

    @MessageMapping("/receipts")
    public void receipt(@Payload ReceiptFrame frame, Principal principal) {
        ChatUserPrincipal me = (ChatUserPrincipal) principal;
        handle(me, frame.conversationId(), null, () -> {
            if ("read".equalsIgnoreCase(frame.kind())) {
                receipts.markRead(me.tenant(), me.userId(), frame.conversationId(), frame.seq());
            } else {
                receipts.markDelivered(me.tenant(), me.userId(), frame.conversationId(), frame.seq());
            }
        });
    }

    @MessageMapping("/presence.watch")
    public void watch(@Payload WatchFrame frame, Principal principal, @Header("simpSessionId") String sessionId) {
        ChatUserPrincipal me = (ChatUserPrincipal) principal;
        handle(me, null, null, () -> presence.watch(sessionId, me, frame.userIds() == null ? List.of() : frame.userIds()));
    }

    private void handle(ChatUserPrincipal me, String conversationId, String clientMsgId, Runnable operation) {
        try {
            operation.run();
        } catch (DomainException e) {
            error(me, conversationId, new ErrorPayload(e.getCode(), e.getMessage(), clientMsgId));
        } catch (RuntimeException e) {
            log.error("Socket operation failed for {}", me.getName(), e);
            error(me, conversationId, new ErrorPayload(ErrorCode.INTERNAL.code(), "Unexpected server error", clientMsgId));
        }
    }

    private void error(ChatUserPrincipal me, String conversationId, ErrorPayload payload) {
        gateway.deliverLocally(me.tenant(), me.userId(), ChatEvent.of(ChatEvent.ERROR, conversationId, payload));
    }
}
