package com.ayush.chat.chat.port;

import java.util.Collection;
import java.util.Map;

/** Read side of presence, as the application services need it. */
public interface PresenceQuery {

    boolean isOnline(String tenant, String userId);

    /** Online flag for each distinct user id. */
    Map<String, Boolean> onlineMany(String tenant, Collection<String> userIds);
}
