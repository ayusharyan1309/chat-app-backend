package com.ayush.chat.store;

import com.ayush.chat.store.model.ChatMessage;

/**
 * Builds the short inbox preview for a message. Shared by all store implementations so every
 * backend renders identical previews.
 */
public final class MessagePreview {

    private static final int MAX = 120;

    private MessagePreview() {
    }

    public static String of(ChatMessage m) {
        if (m.isDeleted()) {
            return "This message was deleted";
        }
        String text = m.getContent();
        if (text == null || text.isBlank()) {
            text = switch (m.getType()) {
                case IMAGE -> "📷 Photo";
                case VIDEO -> "🎥 Video";
                case AUDIO -> "🎤 Voice message";
                case FILE -> "📄 " + (m.getAttachment() != null && m.getAttachment().getFileName() != null
                        ? m.getAttachment().getFileName() : "File");
                case LOCATION -> "📍 Location";
                case CONTACT -> "👤 Contact";
                case STICKER -> "Sticker";
                default -> "";
            };
        }
        return text.length() > MAX ? text.substring(0, MAX - 1) + "…" : text;
    }
}
