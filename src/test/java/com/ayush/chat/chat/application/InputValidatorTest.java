package com.ayush.chat.chat.application;

import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.core.error.ErrorCode;
import com.ayush.chat.store.model.ChatMessage;
import com.ayush.chat.store.model.FieldLimits;
import com.ayush.chat.tenant.Feature;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InputValidatorTest {

    @Test
    void userIdsAreRestrictedToASafeAlphabet() {
        assertThatCode(() -> InputValidator.userId("user_1.a@b:c+d-e")).doesNotThrowAnyException();
        assertThatThrownBy(() -> InputValidator.userId("has space")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> InputValidator.userId("a".repeat(FieldLimits.USER_ID + 1))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> InputValidator.userId(null)).isInstanceOf(DomainException.class);
    }

    @Test
    void fieldLimitsAreBadRequests() {
        assertThatThrownBy(() -> InputValidator.profile("x".repeat(FieldLimits.DISPLAY_NAME + 1), null, null, null, null))
                .isInstanceOfSatisfying(DomainException.class, e -> assertCode(e, ErrorCode.BAD_REQUEST))
                .hasMessageContaining("displayName");
        assertThatThrownBy(() -> InputValidator.emoji("😀".repeat(FieldLimits.EMOJI_CODE_POINTS + 1)))
                .isInstanceOf(DomainException.class);
        assertThatCode(() -> InputValidator.emoji(null)).doesNotThrowAnyException();
    }

    @Test
    void messageTypesHaveTheirOwnPayloadRules() {
        var tenant = ChatFixture.snapshot(Set.of());
        var attachment = ChatMessage.Attachment.builder().url("https://x/y.png").build();
        assertThatThrownBy(() -> InputValidator.content(tenant, ChatMessage.Type.TEXT, " ", null, null))
                .hasMessageContaining("content is required");
        assertThatThrownBy(() -> InputValidator.content(tenant, ChatMessage.Type.TEXT, "hi", attachment, null))
                .hasMessageContaining("cannot carry an attachment");
        assertThatThrownBy(() -> InputValidator.content(tenant, ChatMessage.Type.LOCATION, null, null, Map.of()))
                .hasMessageContaining("metadata");
        assertThatThrownBy(() -> InputValidator.content(tenant, ChatMessage.Type.IMAGE, null, attachment, null))
                .isInstanceOfSatisfying(DomainException.class, e -> assertCode(e, ErrorCode.FEATURE_DISABLED));
        var withMedia = ChatFixture.snapshot(Set.of(Feature.MEDIA_SHARING));
        assertThatCode(() -> InputValidator.content(withMedia, ChatMessage.Type.IMAGE, null, attachment, null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> InputValidator.text(tenant, "x".repeat(4097), true)).hasMessageContaining("maximum length");
    }

    private static void assertCode(DomainException e, ErrorCode code) {
        assertThat(e.getErrorCode()).isEqualTo(code);
    }
}
