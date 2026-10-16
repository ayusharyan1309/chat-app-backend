package com.ayush.chat.core;

import com.ayush.chat.core.error.ApiException;
import com.ayush.chat.core.net.OutboundUrlPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundUrlPolicyTest {

    private final OutboundUrlPolicy strict = new OutboundUrlPolicy(false);

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/hook", "http://localhost:8080/hook", "http://169.254.169.254/latest/meta-data",
            "http://10.0.0.5/x", "http://172.16.3.4/x", "http://192.168.1.1/x", "http://100.64.0.1/x",
            "http://[::1]/x", "http://[fd00::1]/x", "http://0.0.0.0/x"})
    void rejectsInternalTargets(String url) {
        assertThatThrownBy(() -> strict.check(url)).isInstanceOf(ApiException.class).hasMessageContaining("internal");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.com/x", "file:///etc/passwd", "gopher://example.com"})
    void rejectsNonHttpSchemes(String url) {
        assertThatThrownBy(() -> strict.check(url)).isInstanceOf(ApiException.class);
    }

    @Test
    void acceptsPublicAddressesAndPrivateWhenAllowed() {
        assertThatCode(() -> strict.check("https://93.184.215.14/webhook")).doesNotThrowAnyException();
        assertThatCode(() -> new OutboundUrlPolicy(true).check("http://127.0.0.1:9000/hook")).doesNotThrowAnyException();
    }
}
