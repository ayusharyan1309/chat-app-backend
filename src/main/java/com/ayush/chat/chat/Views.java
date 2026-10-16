package com.ayush.chat.chat;

import com.ayush.chat.store.model.ChatMessage;
import com.ayush.chat.store.model.ChatUser;
import com.ayush.chat.store.model.Conversation;
import com.ayush.chat.store.model.Participant;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Public representations returned by the client API and pushed over the socket.
 */
public final class Views {

    private Views() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserView(String id, String displayName, String email, String phone, String avatarUrl, String about,
                           Map<String, Object> metadata, boolean deactivated, Instant createdAt, Instant lastSeenAt,
                           Boolean online) {

        public static UserView of(ChatUser u, boolean includePresence, Boolean online) {
            return new UserView(u.getId(), u.getDisplayName(), u.getEmail(), u.getPhone(), u.getAvatarUrl(), u.getAbout(),
                    u.getMetadata(), u.isDeactivated(), u.getCreatedAt(), includePresence ? u.getLastSeenAt() : null,
                    includePresence ? online : null);
        }

        /** Minimal view for users that no longer exist in the store. */
        public static UserView unknown(String id) {
            return new UserView(id, id, null, null, null, null, null, true, null, null, null);
        }
    }

    public record LastMessage(String id, String preview, String senderId, Instant at) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ParticipantView(String userId, Participant.Role role, String displayName, String avatarUrl,
                                  long deliveredSeq, long readSeq, Instant joinedAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ConversationView(String id, Conversation.Type type, String title, String description, String avatarUrl,
                                   String createdBy, Instant createdAt, long lastSeq, LastMessage lastMessage,
                                   long unreadCount, boolean muted, boolean archived, boolean pinned,
                                   Participant.Role role, UserView peer, List<ParticipantView> participants) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MessageView(String id, String conversationId, long seq, String senderId, String clientMsgId,
                              ChatMessage.Type type, String content, ChatMessage.Attachment attachment, String replyToId,
                              boolean forwarded, Map<String, Set<String>> reactions, boolean edited, Instant editedAt,
                              boolean deleted, Instant createdAt, Map<String, Object> metadata) {

        public static MessageView of(ChatMessage m) {
            return new MessageView(m.getId(), m.getConversationId(), m.getSeq(), m.getSenderId(), m.getClientMsgId(),
                    m.getType(), m.getContent(), m.getAttachment(), m.getReplyToId(), m.isForwarded(),
                    m.getReactions() == null ? Map.of() : m.getReactions(), m.isEdited(), m.getEditedAt(), m.isDeleted(),
                    m.getCreatedAt(), m.getMetadata());
        }
    }

    public static ConversationView conversation(Conversation c, Participant self, UserView peer,
                                                List<ParticipantView> participants) {
        LastMessage last = c.getLastMessageId() == null ? null
                : new LastMessage(c.getLastMessageId(), c.getLastMessagePreview(), c.getLastMessageSenderId(), c.getLastMessageAt());
        long floor = self == null ? c.getLastSeq() : Math.max(self.getReadSeq(), self.getJoinedSeq());
        String title = c.getType() == Conversation.Type.DIRECT && peer != null ? peer.displayName() : c.getTitle();
        String avatar = c.getType() == Conversation.Type.DIRECT && peer != null ? peer.avatarUrl() : c.getAvatarUrl();
        return new ConversationView(c.getId(), c.getType(), title, c.getDescription(), avatar, c.getCreatedBy(),
                c.getCreatedAt(), c.getLastSeq(), last, Math.max(0, c.getLastSeq() - floor),
                self != null && self.isMuted(), self != null && self.isArchived(), self != null && self.isPinned(),
                self == null ? null : self.getRole(), peer, participants);
    }
}
