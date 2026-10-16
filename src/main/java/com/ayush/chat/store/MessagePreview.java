package com.ayush.chat.store;

import com.ayush.chat.store.model.ChatMessage;

/**
 * Builds the short preview of a message: the inbox's last-message line and push notification
 * bodies. Shared by every store (and the push dispatcher) so all backends render identical text.
 */
public final class MessagePreview {

    private static final int MAX = 120;

    private MessagePreview() {
    }

    public static String of(ChatMessage m) {
        return of(m.getType(), m.getContent(), m.getAttachment() == null ? null : m.getAttachment().getFileName(),
                m.isDeleted());
    }

    /**
     * Preview from a message's parts, for callers holding a view rather than a {@link ChatMessage}.
     * Text wins (truncated to 120 characters); without text the type gets a label.
     *
     * @param type     null is treated as {@link ChatMessage.Type#TEXT}
     * @param fileName the attachment's file name, shown for files; may be null
     */
    public static String of(ChatMessage.Type type, String content, String fileName, boolean deleted) {
        if (deleted) {
            return "This message was deleted";
        }
        String text = content;
        if (text == null || text.isBlank()) {
            text = switch (type == null ? ChatMessage.Type.TEXT : type) {
                case IMAGE -> "📷 Photo";
                case VIDEO -> "🎥 Video";
                case AUDIO -> "🎤 Voice message";
                case FILE -> "📄 " + (fileName != null && !fileName.isBlank() ? fileName : "File");
                case LOCATION -> "📍 Location";
                case CONTACT -> "👤 Contact";
                case STICKER -> "Sticker";
                case TEXT, SYSTEM -> "";
            };
        }
        if (text.length() <= MAX) {
            return text;
        }
        int end = MAX - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--; // never split an emoji (or any surrogate pair) in half
        }
        return text.substring(0, end) + "…";
    }
}
