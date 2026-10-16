package com.ayush.chat.core.crypto;

import com.ayush.chat.core.config.PlatformProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Derives independent 256-bit keys from the master key, one per purpose (secret encryption, media
 * URL signing, JWT signing, ...), so a key leaked from one use cannot be replayed in another.
 */
@Slf4j
@Component
public class KeyRing {

    /** Prefixes are part of the derivation and must never change, or existing keys stop matching. */
    public enum Purpose {
        SECRETS(""), MEDIA_URLS("media:"), JWT("jwt:");

        private final String prefix;

        Purpose(String prefix) {
            this.prefix = prefix;
        }
    }

    private final String masterKey;

    public KeyRing(PlatformProperties properties) {
        this.masterKey = properties.getSecurity().effectiveMasterKey();
        if (properties.getSecurity().isUsingDevMasterKey()) {
            log.warn("platform.security.master-key is not set — using an insecure development key. "
                    + "Set PLATFORM_MASTER_KEY before storing real credentials.");
        }
    }

    public byte[] derive(Purpose purpose) {
        return sha256(purpose.prefix + masterKey);
    }

    /** Key derived from an explicitly configured secret rather than the master key. */
    public static byte[] fromSecret(String secret) {
        return sha256(secret);
    }

    private static byte[] sha256(String material) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
