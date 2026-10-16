package com.ayush.chat.core.crypto;

import com.ayush.chat.core.config.PlatformProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoTest {

    private static KeyRing keyRing(String masterKey) {
        PlatformProperties properties = new PlatformProperties();
        properties.getSecurity().setMasterKey(masterKey);
        return new KeyRing(properties);
    }

    @Test
    void derivedKeysAreIndependentPerPurpose() {
        KeyRing ring = keyRing("a-master-key-of-sufficient-length-123");
        assertThat(ring.derive(KeyRing.Purpose.SECRETS)).hasSize(32)
                .isNotEqualTo(ring.derive(KeyRing.Purpose.MEDIA_URLS))
                .isNotEqualTo(ring.derive(KeyRing.Purpose.JWT));
        assertThat(ring.derive(KeyRing.Purpose.JWT)).isEqualTo(keyRing("a-master-key-of-sufficient-length-123")
                .derive(KeyRing.Purpose.JWT));
    }

    @Test
    void secretCipherRoundTripsWithRandomIv() {
        SecretCipher cipher = new SecretCipher(keyRing("k1-0123456789abcdef0123456789"));
        String a = cipher.encrypt("s3cret");
        String b = cipher.encrypt("s3cret");
        assertThat(a).startsWith("enc:v1:").isNotEqualTo(b);
        assertThat(cipher.decrypt(a)).isEqualTo("s3cret");
        assertThat(cipher.encrypt(a)).as("already encrypted values are left alone").isEqualTo(a);
        assertThat(cipher.decrypt("plain")).isEqualTo("plain");
    }

    @Test
    void secretCipherRejectsAnotherMasterKey() {
        String encrypted = new SecretCipher(keyRing("k1-0123456789abcdef0123456789")).encrypt("s3cret");
        SecretCipher other = new SecretCipher(keyRing("k2-0123456789abcdef0123456789"));
        assertThatThrownBy(() -> other.decrypt(encrypted)).hasMessageContaining("master-key");
    }

    @Test
    void webhookSignatureCoversTimestampAndBody() {
        String signature = WebhookSignature.sign("whsec_x", 1700000000L, "{\"a\":1}");
        assertThat(signature).startsWith("sha256=");
        assertThat(WebhookSignature.verify("whsec_x", 1700000000L, "{\"a\":1}", signature)).isTrue();
        assertThat(WebhookSignature.verify("whsec_x", 1700000001L, "{\"a\":1}", signature)).isFalse();
        assertThat(WebhookSignature.verify("whsec_x", 1700000000L, "{\"a\":2}", signature)).isFalse();
        assertThat(WebhookSignature.verify("whsec_y", 1700000000L, "{\"a\":1}", signature)).isFalse();
    }

    @Test
    void hmacSignerIsDeterministicAndComparesInConstantTime() {
        HmacSigner signer = HmacSigner.ofSecret("secret");
        assertThat(signer.signBase64Url("x")).isEqualTo(HmacSigner.ofSecret("secret").signBase64Url("x"))
                .doesNotContain("=", "+", "/");
        assertThat(HmacSigner.matches(signer.signHex("x"), signer.signHex("x"))).isTrue();
        assertThat(HmacSigner.matches(signer.signHex("x"), signer.signHex("y"))).isFalse();
        assertThat(HmacSigner.matches(signer.signHex("x"), null)).isFalse();
    }

    @Test
    void apiKeyHashIsStableHex() {
        assertThat(SecretCipher.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
