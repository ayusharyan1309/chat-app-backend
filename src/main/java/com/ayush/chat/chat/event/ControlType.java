package com.ayush.chat.chat.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/** Kinds of cluster control messages. The ids are the wire format and must stay stable. */
public enum ControlType {
    TENANT_CHANGED("tenant.changed"),
    USER_DEACTIVATED("user.deactivated"),
    CONVERSATION_CHANGED("conversation.changed"),
    BLOCK_CHANGED("block.changed"),
    WEBHOOKS_CHANGED("webhooks.changed"),
    PLANS_CHANGED("plans.changed"),
    /** Sent by a newer node; ignored so rolling upgrades never break the control channel. */
    UNKNOWN("unknown");

    private final String id;

    ControlType(String id) {
        this.id = id;
    }

    @JsonValue
    public String id() {
        return id;
    }

    @JsonCreator
    public static ControlType fromId(String id) {
        return Arrays.stream(values()).filter(t -> t.id.equals(id)).findFirst().orElse(UNKNOWN);
    }
}
