package com.ayush.chat.chat.service;

import com.ayush.chat.core.exception.DomainException;
import com.ayush.chat.store.model.ChatMessage;
import com.ayush.chat.store.model.FieldLimits;
import com.ayush.chat.tenant.model.Feature;
import com.ayush.chat.tenant.model.TenantSnapshot;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Input rules of the messaging domain. Field limits match the store schemas, so a violation is
 * always a 400 and never a database error. Pure functions, so they are trivially testable.
 */
final class InputValidator {

    private static final Pattern USER_ID = Pattern.compile("^[A-Za-z0-9_.@:+-]{1," + FieldLimits.USER_ID + "}$");

    private InputValidator() {
    }

    static void userId(String id) {
        if (id == null || !USER_ID.matcher(id).matches()) {
            throw DomainException.badRequest("id must be 1-" + FieldLimits.USER_ID
                    + " chars of letters, digits and _ . @ : + -");
        }
    }

    static void profile(String displayName, String email, String phone, String avatarUrl, String about) {
        maxLength("displayName", displayName, FieldLimits.DISPLAY_NAME);
        maxLength("email", email, FieldLimits.EMAIL);
        maxLength("phone", phone, FieldLimits.PHONE);
        maxLength("avatarUrl", avatarUrl, FieldLimits.URL);
        maxLength("about", about, FieldLimits.ABOUT);
    }

    static void groupTitle(String title) {
        if (title == null || title.isBlank() || title.length() > FieldLimits.TITLE) {
            throw DomainException.badRequest("title is required (max " + FieldLimits.TITLE + " characters)");
        }
    }

    static void groupDetails(String title, String description, String avatarUrl) {
        maxLength("title", title, FieldLimits.TITLE);
        maxLength("description", description, FieldLimits.DESCRIPTION);
        maxLength("avatarUrl", avatarUrl, FieldLimits.URL);
    }

    static void deviceToken(String token) {
        if (token == null || token.isBlank() || token.length() > FieldLimits.DEVICE_TOKEN) {
            throw DomainException.badRequest("A device token is required (max " + FieldLimits.DEVICE_TOKEN + " characters)");
        }
    }

    static void clientMsgId(String clientMsgId) {
        maxLength("clientMsgId", clientMsgId, FieldLimits.CLIENT_MSG_ID);
    }

    static void emoji(String emoji) {
        if (emoji != null && (emoji.isBlank() || emoji.codePointCount(0, emoji.length()) > FieldLimits.EMOJI_CODE_POINTS)) {
            throw DomainException.badRequest("emoji must be a short emoji string");
        }
    }

    /** Type-specific payload rules plus the feature each type needs. */
    static void content(TenantSnapshot tenant, ChatMessage.Type type, String content, ChatMessage.Attachment attachment,
                        Map<String, Object> metadata) {
        switch (type) {
            case IMAGE, VIDEO, AUDIO, STICKER -> {
                tenant.require(Feature.MEDIA_SHARING);
                requireAttachment(attachment);
                text(tenant, content, false);
            }
            case FILE -> {
                tenant.require(Feature.FILE_SHARING);
                requireAttachment(attachment);
                text(tenant, content, false);
            }
            case TEXT, SYSTEM -> {
                rejectAttachment(attachment, type);
                text(tenant, content, true);
            }
            case LOCATION, CONTACT -> {
                rejectAttachment(attachment, type);
                if (metadata == null || metadata.isEmpty()) {
                    throw DomainException.badRequest(type + " messages carry their payload in metadata");
                }
                text(tenant, content, false);
            }
        }
    }

    static void text(TenantSnapshot tenant, String content, boolean required) {
        if (content == null || content.isBlank()) {
            if (required) {
                throw DomainException.badRequest("content is required");
            }
            return;
        }
        int max = tenant.settings().maxMessageLength();
        if (content.length() > max) {
            throw DomainException.badRequest("Message exceeds the maximum length of " + max + " characters");
        }
    }

    private static void rejectAttachment(ChatMessage.Attachment attachment, ChatMessage.Type type) {
        if (attachment != null) {
            throw DomainException.badRequest(type + " messages cannot carry an attachment; use IMAGE, VIDEO, AUDIO or FILE");
        }
    }

    private static void requireAttachment(ChatMessage.Attachment attachment) {
        if (attachment == null || attachment.getUrl() == null) {
            throw DomainException.badRequest("This message type requires an attachment with a url");
        }
    }

    static void maxLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw DomainException.badRequest(field + " must be at most " + max + " characters");
        }
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
