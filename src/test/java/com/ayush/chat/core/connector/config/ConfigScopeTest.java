package com.ayush.chat.core.connector.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigScopeTest {

    @Test
    void keysRoundTrip() {
        assertThat(ConfigScope.PLATFORM.key()).isEqualTo("platform");
        assertThat(ConfigScope.parse("platform")).isEqualTo(ConfigScope.PLATFORM);
        assertThat(ConfigScope.tenant("acme").key()).isEqualTo("tenant:acme");
        assertThat(ConfigScope.parse("tenant:acme")).isEqualTo(ConfigScope.tenant("acme"));
        assertThat(ConfigScope.parse("tenant:acme").isPlatform()).isFalse();
    }

    @Test
    void rejectsUnknownKeys() {
        assertThatThrownBy(() -> ConfigScope.parse("global")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigScope.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
