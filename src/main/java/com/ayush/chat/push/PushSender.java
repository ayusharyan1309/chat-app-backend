package com.ayush.chat.push;

import com.ayush.chat.core.connector.Connector;

import java.util.Map;
import java.util.Set;

/**
 * Delivers notifications to offline users' devices.
 */
public interface PushSender extends Connector {

    PushResult send(PushNotification notification);

    record PushNotification(String tenantId, String userId, Set<String> deviceTokens,
                            String title, String body, Map<String, String> data) {
    }

    /** {@code invalidTokens} should be removed from the user's profile by the caller. */
    record PushResult(int delivered, int failed, Set<String> invalidTokens) {
        public static PushResult none() {
            return new PushResult(0, 0, Set.of());
        }
    }
}
