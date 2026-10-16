package com.ayush.chat.store;

import com.ayush.chat.store.model.ChatUser;

import java.util.Locale;

/**
 * The matching rule of {@link MessageStore#searchUsers}: a case-insensitive substring match on id,
 * display name and email. Stores that filter users in memory share it so every backend returns the
 * same users. Case folding uses {@link Locale#ROOT}, so results never depend on the server's default
 * locale (in a Turkish locale, {@code "ID".toLowerCase()} is not {@code "id"}).
 */
public final class UserSearch {

    private UserSearch() {
    }

    /** The query as matched: trimmed and lower-cased; empty (matches everyone) when null or blank. */
    public static String normalize(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }

    /** Whether the user matches a query already passed through {@link #normalize}. */
    public static boolean matches(ChatUser user, String normalizedQuery) {
        return normalizedQuery.isEmpty()
                || contains(user.getId(), normalizedQuery)
                || contains(user.getDisplayName(), normalizedQuery)
                || contains(user.getEmail(), normalizedQuery);
    }

    private static boolean contains(String value, String normalizedQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }
}
