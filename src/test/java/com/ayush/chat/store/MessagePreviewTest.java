package com.ayush.chat.store;

import com.ayush.chat.store.model.ChatMessage;
import com.ayush.chat.store.model.ChatMessage.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessagePreviewTest {

    @Test
    void textWinsOverTheTypeLabel() {
        assertThat(MessagePreview.of(Type.IMAGE, "look at this", null, false)).isEqualTo("look at this");
        assertThat(MessagePreview.of(Type.TEXT, "hello", null, false)).isEqualTo("hello");
    }

    @Test
    void everyTypeWithoutTextHasALabel() {
        assertThat(MessagePreview.of(Type.IMAGE, null, null, false)).isEqualTo("📷 Photo");
        assertThat(MessagePreview.of(Type.VIDEO, " ", null, false)).isEqualTo("🎥 Video");
        assertThat(MessagePreview.of(Type.AUDIO, null, null, false)).isEqualTo("🎤 Voice message");
        assertThat(MessagePreview.of(Type.FILE, null, "report.pdf", false)).isEqualTo("📄 report.pdf");
        assertThat(MessagePreview.of(Type.FILE, null, null, false)).isEqualTo("📄 File");
        assertThat(MessagePreview.of(Type.LOCATION, null, null, false)).isEqualTo("📍 Location");
        assertThat(MessagePreview.of(Type.CONTACT, null, null, false)).isEqualTo("👤 Contact");
        assertThat(MessagePreview.of(Type.STICKER, null, null, false)).isEqualTo("Sticker");
        assertThat(MessagePreview.of(Type.SYSTEM, null, null, false)).isEmpty();
        assertThat(MessagePreview.of(null, null, null, false)).isEmpty();
    }

    @Test
    void deletedMessagesHideTheirContent() {
        assertThat(MessagePreview.of(Type.TEXT, "secret", null, true)).isEqualTo("This message was deleted");
    }

    @Test
    void longTextIsTruncatedWithoutSplittingAnEmoji() {
        String text = "x".repeat(200);
        assertThat(MessagePreview.of(Type.TEXT, text, null, false)).hasSize(120).endsWith("…");

        String emojiAtCut = "x".repeat(118) + "😀" + "tail";
        String preview = MessagePreview.of(Type.TEXT, emojiAtCut, null, false);
        assertThat(preview).isEqualTo("x".repeat(118) + "…");
    }

    @Test
    void messageOverloadMatchesTheViewOverload() {
        ChatMessage file = ChatMessage.builder().type(Type.FILE)
                .attachment(ChatMessage.Attachment.builder().fileName("a.zip").build()).build();
        assertThat(MessagePreview.of(file)).isEqualTo(MessagePreview.of(Type.FILE, null, "a.zip", false));
        ChatMessage deleted = ChatMessage.builder().content("x").deleted(true).build();
        assertThat(MessagePreview.of(deleted)).isEqualTo("This message was deleted");
    }
}
