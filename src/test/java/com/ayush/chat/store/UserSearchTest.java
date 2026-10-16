package com.ayush.chat.store;

import com.ayush.chat.store.memory.InMemoryMessageStore;
import com.ayush.chat.store.model.ChatUser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class UserSearchTest {

    private static final ChatUser USER = ChatUser.builder().tenantId("t").id("kim-42").displayName("Kim Lee")
            .email("Kim.Lee@Example.com").build();

    @Test
    void matchesIdDisplayNameOrEmailIgnoringCase() {
        assertThat(UserSearch.matches(USER, UserSearch.normalize("KIM-4"))).isTrue();
        assertThat(UserSearch.matches(USER, UserSearch.normalize(" lee "))).isTrue();
        assertThat(UserSearch.matches(USER, UserSearch.normalize("example.COM"))).isTrue();
        assertThat(UserSearch.matches(USER, UserSearch.normalize("bob"))).isFalse();
    }

    @Test
    void blankQueryMatchesEveryone() {
        assertThat(UserSearch.normalize(null)).isEmpty();
        assertThat(UserSearch.normalize("   ")).isEmpty();
        assertThat(UserSearch.matches(ChatUser.builder().id("x").build(), "")).isTrue();
    }

    /** In a Turkish locale "I".toLowerCase() is a dotless "ı", which would make "KIM" miss "kim". */
    @Test
    void matchingDoesNotDependOnTheDefaultLocale() {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertThat(UserSearch.matches(USER, UserSearch.normalize("KIM"))).isTrue();

            InMemoryMessageStore store = new InMemoryMessageStore();
            store.upsertUser(USER.toBuilder().createdAt(Instant.now()).updatedAt(Instant.now()).build());
            assertThat(store.searchUsers("t", "KIM", 0, 10)).extracting(ChatUser::getId).containsExactly("kim-42");
        } finally {
            Locale.setDefault(original);
        }
    }
}
