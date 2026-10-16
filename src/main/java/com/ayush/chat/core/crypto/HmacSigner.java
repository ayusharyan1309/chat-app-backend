package com.ayush.chat.core.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * HMAC-SHA256 with constant-time verification.
 */
public final class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public HmacSigner(byte[] key) {
        this.key = new SecretKeySpec(key, ALGORITHM);
    }

    public static HmacSigner ofSecret(String secret) {
        return new HmacSigner(secret.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public String signHex(String payload) {
        return HexFormat.of().formatHex(sign(payload.getBytes(StandardCharsets.UTF_8)));
    }

    public String signBase64Url(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload.getBytes(StandardCharsets.UTF_8)));
    }

    /** Constant-time comparison of a presented signature against the expected one. */
    public static boolean matches(String expected, String presented) {
        return presented != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
