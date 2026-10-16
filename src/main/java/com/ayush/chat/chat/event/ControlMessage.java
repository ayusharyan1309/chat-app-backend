package com.ayush.chat.chat.event;

/**
 * Cluster control message: tells every node to drop cached state or close sockets after an
 * administrative change. {@code ref} identifies the affected object (conversation id, block pair).
 */
public record ControlMessage(ControlType type, String tenant, String userId, String ref) {

    public static ControlMessage tenantChanged(String tenant) {
        return new ControlMessage(ControlType.TENANT_CHANGED, tenant, null, null);
    }

    public static ControlMessage userDeactivated(String tenant, String userId) {
        return new ControlMessage(ControlType.USER_DEACTIVATED, tenant, userId, null);
    }

    public static ControlMessage conversationChanged(String tenant, String conversationId) {
        return new ControlMessage(ControlType.CONVERSATION_CHANGED, tenant, null, conversationId);
    }

    public static ControlMessage blockChanged(String tenant, String blocker, String blocked) {
        return new ControlMessage(ControlType.BLOCK_CHANGED, tenant, null, blocker + "|" + blocked);
    }

    /** Plans are shared by many tenants, so every cached snapshot is dropped. */
    public static ControlMessage plansChanged() {
        return new ControlMessage(ControlType.PLANS_CHANGED, null, null, null);
    }

    public static ControlMessage webhooksChanged(String tenant) {
        return new ControlMessage(ControlType.WEBHOOKS_CHANGED, tenant, null, null);
    }
}
