package com.ayush.chat.store.model;

/**
 * A conversation as seen from one participant's inbox.
 */
public record InboxItem(Conversation conversation, Participant self) {

    public long unreadCount() {
        long floor = Math.max(self.getReadSeq(), self.getJoinedSeq());
        return Math.max(0, conversation.getLastSeq() - floor);
    }
}
