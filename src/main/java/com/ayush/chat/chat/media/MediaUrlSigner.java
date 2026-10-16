package com.ayush.chat.chat.media;

import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.crypto.HmacSigner;
import com.ayush.chat.core.crypto.KeyRing;
import org.springframework.stereotype.Component;

/**
 * Capability URLs for media: {@code /api/v1/media/{tenant}/{mediaId}?sig=...}. The signature is an
 * HMAC under a key derived for this purpose only, so the URL works in {@code <img src>} without
 * auth headers yet cannot be forged or moved to another tenant.
 */
@Component
public class MediaUrlSigner {

    private final HmacSigner signer;
    private final String baseUrl;

    public MediaUrlSigner(KeyRing keyRing, PlatformProperties properties) {
        this.signer = new HmacSigner(keyRing.derive(KeyRing.Purpose.MEDIA_URLS));
        String publicUrl = properties.getPublicUrl();
        this.baseUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    public String url(String tenant, String mediaId) {
        return baseUrl + "/api/v1/media/" + tenant + "/" + mediaId + "?sig=" + signature(tenant, mediaId);
    }

    public boolean verify(String tenant, String mediaId, String presented) {
        return presented != null && HmacSigner.matches(signature(tenant, mediaId), presented);
    }

    private String signature(String tenant, String mediaId) {
        return signer.signBase64Url(tenant + "/" + mediaId);
    }
}
